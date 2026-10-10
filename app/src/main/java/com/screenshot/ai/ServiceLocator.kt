package com.screenshot.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.screenshot.ai.ai.AiProviderRegistry
import com.screenshot.ai.ai.providers.CustomProvider
import com.screenshot.ai.data.crypto.CryptoRepository
import com.screenshot.ai.data.crypto.CryptoRepositoryImpl
import com.screenshot.ai.data.db.AppDatabase
import com.screenshot.ai.data.db.HistoryRepositoryImpl
import com.screenshot.ai.data.image.ImageStoreImpl
import com.screenshot.ai.data.prefs.CustomModelRepositoryImpl
import com.screenshot.ai.data.prefs.SettingsRepository
import com.screenshot.ai.data.preset.PresetRepositoryImpl
import com.screenshot.ai.data.repo.CustomModelRepository
import com.screenshot.ai.data.repo.HistoryRepository
import com.screenshot.ai.data.repo.ImageStore
import com.screenshot.ai.data.repo.PresetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 轻量依赖容器（手动 DI）。
 *
 * 为什么不用 Hilt：
 * - 本项目依赖关系简单（数据库 + 3 个仓库 + 1 个加密存储），
 *   手写容器没有注解处理开销，构建更快，也更容易被贡献者理解。
 *
 * 使用范围：
 * [com.screenshot.ai.ui.main.MainActivity] 等 Compose 页面之外，
 * [com.screenshot.ai.service.FloatingService]、[com.screenshot.ai.service.ScreenCaptureService]
 * 与 [com.screenshot.ai.service.BootReceiver] 这类「没有 ViewModel 作用域」的组件也依赖它，
 * 因此它必须是进程级单例，且 [init] 必须幂等。
 *
 * 线程约束：
 * - [init] 可在任意线程调用（[initialized] 用 `@Volatile` + 双检锁保证可见性与唯一性）；
 * - 构造函数内不做磁盘 IO（Room/DataStore 都是惰性打开文件），因此主线程调用也不会 ANR。
 */
object ServiceLocator {
    private const val TAG = "ServiceLocator"

    /** 是否已初始化；Service 重启、广播重复触发都可能再调一次 [init] */
    @Volatile
    private var initialized = false

    /** 应用上下文；所有静态依赖统一持有它，避免意外泄漏 Activity */
    private lateinit var appContext: Context

    /**
     * 应用级协程作用域：用于悬浮窗异步任务与长生命周期监听。
     *
     * 用 `SupervisorJob + Dispatchers.Default`：
     * - Supervisor：某个 AI 请求失败不应连坐取消其它请求（例如结果窗口正在进行的流式输出）；
     * - Default：请求组装、JSON 解析、图片压缩都是 CPU/IO 混合负载，
     *   真正需要主线程的 UI 更新由调用方切回 Main。
     */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /** 主线程 Handler：悬浮窗 UI 更新使用（悬浮窗不在任何 Activity 生命周期内，需要自行切线程） */
    val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    // ---------------------------------------------------------------------
    // 数据层
    //
    // 全部声明为 lateinit var 并在 init() 中一次性构造，而不是 by lazy：
    // 惰性求值会把「首次构造」推迟到某个不确定的调用点（可能在主线程的 UI 回调里、
    // 也可能在广播的 10 秒窗口内），而 init() 由 Application.onCreate 统一驱动，
    // 时序确定、失败可见，排查问题要容易得多。
    // ---------------------------------------------------------------------

    /** 截图文件仓库（写入 filesDir/screenshots，不进相册） */
    lateinit var imageStore: ImageStore
        private set

    /** 提问历史仓库 */
    lateinit var historyRepository: HistoryRepository
        private set

    /** 预设指令仓库（整体以 JSON 存进 kv 表） */
    lateinit var presetRepository: PresetRepository
        private set

    /** API Key / BaseUrl 加密存储 */
    lateinit var cryptoRepository: CryptoRepository
        private set

    /** 应用配置（DataStore） */
    lateinit var settingsRepository: SettingsRepository
        private set

    /** 自定义模型（OpenAI 兼容第三方接口）仓库 */
    lateinit var customModelRepository: CustomModelRepository
        private set

    // ---------------------------------------------------------------------
    // 运行状态（供 UI 观察，避免 Compose 直接查询系统服务）
    // ---------------------------------------------------------------------

    /** 悬浮服务是否正在运行；由 [com.screenshot.ai.service.FloatingService] 维护 */
    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    /** 手动触发一次 UI 重算（权限状态变化等非 Flow 场景） */
    private val _refreshTrigger = MutableStateFlow(0L)
    val refreshTrigger: StateFlow<Long> = _refreshTrigger.asStateFlow()

    /**
     * 初始化。必须在 Application.onCreate 中调用一次。
     *
     * 幂等：服务被系统重启、广播重复触发时可能被多次调用；重复构造 DataStore 或数据库
     * 会造成「同一文件多个实例」的并发写入异常，因此这里必须短路返回。
     */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // Room / DataStore / EncryptedSharedPreferences 都长期持有 Context，
            // 统一使用 applicationContext，避免静态单例意外持有 Activity 造成泄漏。
            val application = context.applicationContext
            val database = AppDatabase.get(application)
            val store: ImageStore = ImageStoreImpl(application)
            appContext = application
            imageStore = store
            historyRepository = HistoryRepositoryImpl(database.historyDao(), store)
            presetRepository = PresetRepositoryImpl(database.presetDao())
            cryptoRepository = CryptoRepositoryImpl(application)
            settingsRepository = SettingsRepository(application)
            customModelRepository = CustomModelRepositoryImpl(application)

            // 注入自定义模型 Provider：配置驱动的 OpenAI 兼容接口，
            // 追加在内置 6 家之后（未配置时 all() 仍只有 6 家，不影响单测）
            AiProviderRegistry.customProvider =
                CustomProvider { customModelRepository.cachedConfig() }
            // 保持配置缓存与 DataStore 同步，供 Provider 主线程同步读取
            appScope.launch {
                customModelRepository.observe().collect { config ->
                    customModelRepository.updateCache(config)
                }
            }

            initialized = true
            Log.i(TAG, "ServiceLocator 初始化完成")

            // 3.1.0 数据迁移：历史版本遗留的内置指令转正为普通用户指令（幂等，异步执行）
            appScope.launch {
                runCatching { presetRepository.migrateLegacyBuiltins() }
                    .onFailure { Log.w(TAG, "内置指令迁移失败", it) }
            }
        }
    }

    /** 更新服务运行状态，由 FloatingService 在 onStartCommand/onDestroy 中调用 */
    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    /**
     * 应用 Context（applicationContext）。
     *
     * 仅供确实需要 Context 但又拿不到 Activity 的模块使用（例如示例代码里的缓存目录）。
     * 返回的是 Application 级 Context，因此持有它不会泄漏任何界面。
     */
    fun applicationContext(): Context = appContext

    /** 触发一次状态刷新 */
    fun notifyRefresh() {
        _refreshTrigger.value = System.currentTimeMillis()
    }
}
