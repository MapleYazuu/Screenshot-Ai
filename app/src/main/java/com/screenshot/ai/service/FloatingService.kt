package com.screenshot.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.screenshot.ai.R
import com.screenshot.ai.ServiceLocator
import com.screenshot.ai.ai.AiProviderRegistry
import com.screenshot.ai.ai.ChatSession
import com.screenshot.ai.ai.StreamCallback
import com.screenshot.ai.data.model.HistoryItem
import com.screenshot.ai.data.model.Preset
import com.screenshot.ai.data.prefs.AppSettings
import com.screenshot.ai.ui.main.MainActivity
import com.screenshot.ai.ui.settings.SettingsActivity
import com.screenshot.ai.util.AiErrorMessages
import com.screenshot.ai.util.ImageUtils
import com.screenshot.ai.util.OcrUtils
import com.screenshot.ai.util.PermissionUtils
import com.screenshot.ai.util.PromptTemplates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 悬浮工具栏前台服务。
 *
 * 为什么必须是前台服务：
 * 桌面常驻悬浮球需要在应用退到后台后继续存活，普通后台服务在 Android 8+ 会被随时回收，
 * 只有前台服务（带常驻通知）才能稳定运行。该通知无法被划掉——这是前台服务的固有特性
 * （ongoing notification），也是系统提示用户「应用正在后台持续运行」的方式，
 * 因此通知里必须提供「停止」动作，把控制权还给用户。
 *
 * 三条核心链路（对应悬浮球的三种交互）：
 * 1. 单击（快捷模式）：截屏 -> 落盘 -> 取默认指令 -> 请求 -> 流式渲染 -> 写入历史；
 * 2. 长按：展开操作面板（截图 / 录入 / 历史 / 设置）；
 * 3. 面板按钮：截图走「预览 + 选指令」，录入维护预设，历史可复制 / 删除 / 重新提问。
 *
 * 线程模型：AI 请求、位图编解码与磁盘写入全部在 [ServiceLocator.appScope] 中执行，
 * UI 更新统一通过 [withContextMain] 切回主线程。主线程永远不做重活。
 *
 * 并发模型：同一时刻只允许一条 AI 链路在跑，由 [aiJob] 代表当前链路。
 * 发起新链路前先取消旧的，避免两个流式结果互相覆盖同一块 UI。
 */
class FloatingService : Service() {
    private lateinit var overlayManager: OverlayManager

    /**
     * 当前 AI 链路的 Job。
     *
     * 关闭结果窗口或发起新请求时取消它。注意「不要取消自己」：
     * 链路内部调用 [cancelPreviousAiWork] 时会先比对自己所在的 Job，
     * 否则刚启动的链路会把自己取消掉（表现为点了没反应）。
     */
    private var aiJob: Job? = null

    /** 当前会话；追问与重新生成都要复用它（它持有首轮截图） */
    private var currentSession: ChatSession? = null

    /** 当前会话的标题与提问文本，用于写入历史 */
    private var currentTitle: String = ""
    private var currentPrompt: String = ""

    /** 当前截图落盘路径，用于写入历史记录 */
    private var currentImagePath: String = ""

    /** 当前对话在历史表中的记录 id；0 表示尚未写入 */
    private var currentHistoryId: Long = 0L

    /** 预览面板当前展示的位图；重拍时负责回收，避免旧图泄漏 */
    private var previewBitmap: Bitmap? = null

    /** 待用户确认的截图；确认后才进入 AI 流程 */
    private var pendingCapture: Bitmap? = null

    private var running = false

    /**
     * 历史落库专用作用域。
     *
     * 刻意与 [aiJob] 解耦：历史写入发生在流式回调的**收尾阶段**，
     * 而用户完全可能在同一瞬间点「关闭」触发 [aiJob] 取消。
     * 若把落库跑在 aiJob 里，一次正常的关闭动作就会把刚生成的回复丢掉，
     * 历史里只剩一条空记录。这个作用域只在 [onDestroy] 时取消。
     */
    private val persistScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // 让 isRunning() 在 onCreate 阶段即为 true：ScreenshotAiApp 会在启动时据此判断是否需要补拉起
        runningInstance = this
        overlayManager = OverlayManager(this)
        overlayManager.listener = buildOverlayListener()
        // 订阅配置：透明度 / 尺寸 / 贴边开关变化后立即生效，不需要重启服务
        overlayManager.observeSettings(
            ServiceLocator.appScope,
            ServiceLocator.settingsRepository.settings,
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // 通知栏的「停止」动作：用户主动关闭悬浮工具栏
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "用户从通知栏停止悬浮工具栏")
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        startForegroundCompat(notification)
        // startForeground 显示的是不带动作的精简版，这里补齐「停止」动作（前台服务通知不可划掉）
        notifySafely(notification)

        if (!running) {
            running = true
            // 通知 Compose 主界面：开关状态以「服务真实运行状态」为准
            ServiceLocator.setServiceRunning(true)
            overlayManager.showBubble()
            Log.i(TAG, "悬浮工具栏已启动")
        }

        // START_STICKY：被系统回收后自动重建，与 ScreenshotAiApp 的自愈逻辑形成双保险
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runningInstance = null
        ServiceLocator.setServiceRunning(false)
        // 先取消在途请求，再拆窗口：否则回调可能在 View 已被移除之后才到达
        aiJob?.cancel()
        aiJob = null
        currentSession = null
        releasePreviewBitmap()
        pendingCapture?.recycle()
        pendingCapture = null
        persistScope.cancel()
        overlayManager.dispose()
        // 服务被停止代表开关应复位，否则主界面会显示「已开启」但悬浮球不存在
        ServiceLocator.appScope.launch {
            runCatching { ServiceLocator.settingsRepository.setFloatingEnabled(false) }
                .onFailure { Log.w(TAG, "复位悬浮开关失败", it) }
        }
        Log.i(TAG, "悬浮工具栏已停止")
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // 通知
    // ---------------------------------------------------------------------

    /**
     * 以前台服务身份启动。
     *
     * API 34（Android 14）起，manifest 中声明了 foregroundServiceType 的服务
     * 必须在 startForeground 时同步传入类型，否则抛 MissingForegroundServiceTypeException；
     * 低版本传 0 表示「无特殊类型」。[ServiceCompat.startForeground] 内部做了版本分支，
     * 这里统一走它，避免手写 if/else 漏掉分支。
     */
    private fun startForegroundCompat(notification: Notification) {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    /** 发送 / 更新常驻通知；Android 13+ 未授权时会抛 SecurityException，这里降级为日志 */
    private fun notifySafely(notification: Notification) {
        if (!PermissionUtils.isNotificationPermissionGranted(this)) {
            // 通知权限被拒不影响截屏提问这一核心功能，只是用户看不到常驻通知
            Log.w(TAG, "未授予通知权限，前台服务通知可能不可见")
        }
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "发送前台服务通知被拒绝", e)
        }
    }

    /**
     * 构建常驻通知。
     *
     * setOngoing(true) 让通知在服务存活期间不可划掉；
     * 两个动作分别是「点进主界面」与「停止悬浮工具栏」。
     */
    private fun buildNotification(): Notification {
        createChannelIfNeeded()
        val contentIntent =
            PendingIntent.getActivity(
                this,
                REQUEST_CONTENT,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                REQUEST_STOP,
                Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_overlay_camera)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_overlay_camera,
                getString(R.string.notif_action_stop),
                stopIntent,
            ).setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // 前台服务通知必须常驻，用户只能通过「停止」动作关闭
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .build()
    }

    /** 创建通知渠道：IMPORTANCE_LOW 保证不响铃、不弹横幅，只安静地待在通知栏 */
    private fun createChannelIfNeeded() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    // ---------------------------------------------------------------------
    // 面板回调
    // ---------------------------------------------------------------------

    private fun buildOverlayListener(): OverlayManager.Listener =
        object : OverlayManager.Listener {
            /**
             * 单击悬浮球（状态机 isMenuExpanded = isActionPanelVisible）：
             * - 四周卡片已展开 → 不截图，触发收起动画 + 悬浮球 0.95 缩放反馈；
             * - 未展开 → 快捷模式：直接截图 -> 提问，跳过预览与选指令。
             */
            override fun onBubbleTap() {
                if (overlayManager.isActionPanelVisible()) {
                    overlayManager.hideActionPanel()
                    overlayManager.pulseBubbleFeedback()
                    return
                }
                startAiFlow { runQuickCapture() }
            }

            /** 截图按钮：截屏 -> 预览确认 -> 选择指令 -> 提问 */
            override fun onScreenshot() {
                overlayManager.hideAllPanels()
                startAiFlow { runPreviewCapture() }
            }

            /**
             * 「选择指令」按钮（原「录入」）：
             * 弹出预设指令列表，点击某条即设为当前默认指令（单击悬浮球快捷截图时使用）。
             * 「+ 新建指令」作为列表底部的二级入口，不占一级按钮。
             */
            override fun onInputPreset() {
                ServiceLocator.appScope.launch { showPresetChooser() }
            }

            /** 历史按钮：展示最近记录 */
            override fun onHistory() {
                ServiceLocator.appScope.launch { showHistoryPanel() }
            }

            /** 设置按钮：跳转设置页（悬浮窗没有 Task 栈，必须带 NEW_TASK） */
            override fun onSettings() {
                overlayManager.hideAllPanels()
                startActivity(
                    Intent(this@FloatingService, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }

            /** 结果窗口关闭：取消在途请求，避免回调打到已移除的 View 上 */
            override fun onResultClose() {
                aiJob?.cancel()
                aiJob = null
                currentSession = null
                overlayManager.hideResult()
            }

            /** 重新生成：复用同一会话，保留原图与用户指令 */
            override fun onResultRegenerate() {
                startAiFlow { runRegenerate() }
            }

            /** 追问：同一会话继续对话，截图上下文不会丢失 */
            override fun onResultFollowUp(prompt: String) {
                startAiFlow { runFollowUp(prompt) }
            }

            /** 历史：复制回复到剪贴板 */
            override fun onHistoryCopy(item: HistoryItem) {
                copyToClipboard(item.aiReply)
                ServiceLocator.appScope.launch { showHistoryPanel() }
            }

            /** 历史：删除记录 */
            override fun onHistoryDelete(item: HistoryItem) {
                ServiceLocator.appScope.launch {
                    runCatching { ServiceLocator.historyRepository.delete(item) }
                        .onFailure { Log.w(TAG, "删除历史失败", it) }
                    showHistoryPanel()
                }
            }

            /** 历史：用原始截图与指令重新提问 */
            override fun onHistoryReAsk(item: HistoryItem) {
                overlayManager.hideAllPanels()
                startAiFlow { runReAsk(item) }
            }

            /** 预览确认：先取预设列表，再交给用户选择 */
            override fun onCaptureConfirm() {
                startAiFlow { runPresetPicker() }
            }

            /** 预览重拍：重新截屏并刷新预览图 */
            override fun onCaptureRetry() {
                startAiFlow { runPreviewCapture() }
            }
        }

    // ---------------------------------------------------------------------
    // 链路一：单击快捷模式
    // ---------------------------------------------------------------------

    /**
     * 快捷模式：截屏后直接使用默认指令提问。
     *
     * 与「截图按钮」链路的差别只在「不预览、不选指令」，
     * 目的是一步到位地回答「屏幕上这是什么」这类高频问题。
     */
    private suspend fun runQuickCapture() {
        val bitmap = captureScreenOrReport() ?: return
        try {
            runQuestion(bitmap, preset = null)
        } finally {
            bitmap.recycle()
        }
    }

    // ---------------------------------------------------------------------
    // 链路二：截图按钮（预览 -> 选指令 -> 提问）
    // ---------------------------------------------------------------------

    /** 截屏并在悬浮窗中展示预览，等待用户确认（防抖：流程进行中重复触发直接忽略） */
    private suspend fun runPreviewCapture() {
        if (previewFlowActive) return
        previewFlowActive = true
        try {
            withContextMain { overlayManager.showCapturePreview() }
            val bitmap = captureScreenOrReport()
            if (bitmap == null) {
                withContextMain { overlayManager.hideCapturePreview() }
                return
            }
            // 预览只用于「看」：缩到预览尺寸，避免为了显示多留一份原图内存
            val preview = ImageUtils.compressForUpload(bitmap, maxEdge = PREVIEW_MAX_EDGE, quality = 80)
            withContextMain {
                // 重拍时旧预览图必须先回收，否则每重拍一次就多占一份位图内存
                releasePreviewBitmap()
                previewBitmap = preview
                overlayManager.updatePreviewBitmap(preview)
                overlayManager.updatePreviewTip(null)
            }
            // 上一张待确认截图（例如用户连点两次截图）同样需要回收
            pendingCapture?.recycle()
            pendingCapture = bitmap
        } finally {
            previewFlowActive = false
        }
    }

    /** 预览截图流程进行中标志（防抖，连点不会叠加多个预览窗口） */
    private var previewFlowActive = false

    /** 预览确认后弹出预设指令选择面板 */
    private suspend fun runPresetPicker() {
        val presets = loadPresets()
        if (presets.isEmpty()) {
            withContextMain {
                overlayManager.hideCapturePreview()
            }
            recyclePendingCapture()
            withContextMain {
                showResultMessage(
                    title = getString(R.string.overlay_result_title),
                    message = getString(R.string.overlay_no_preset),
                )
            }
            return
        }
        withContextMain {
            overlayManager.hideCapturePreview()
            overlayManager.chooseFromPresets(presets.map { PresetOption.from(it) }) { chosen ->
                // 选择回调发生在主线程且已关闭面板，这里再切回后台跑 AI 链路
                startAiFlow { runPickedPreset(chosen) }
            }
        }
    }

    /** 用户选好指令后发起提问 */
    private suspend fun runPickedPreset(chosen: PresetOption) {
        val bitmap = pendingCapture
        if (bitmap == null) {
            Log.w(TAG, "待确认截图已释放，忽略本次指令选择")
            return
        }
        pendingCapture = null
        releasePreviewBitmap()
        try {
            runQuestion(bitmap, preset = chosen.toPresetForRun())
        } finally {
            bitmap.recycle()
        }
    }

    // ---------------------------------------------------------------------
    // 链路三：历史记录
    // ---------------------------------------------------------------------

    /** 读取最近的历史记录并展示在面板中（防抖：加载中重复点击直接忽略） */
    private suspend fun showHistoryPanel() {
        if (historyPanelLoading) return
        historyPanelLoading = true
        try {
            val items =
                runCatching {
                    withTimeoutOrNull(HISTORY_TIMEOUT_MS) {
                        ServiceLocator.historyRepository.observeAll().first()
                    }
                }.getOrNull().orEmpty()
            withContextMain {
                // 面板内部已按 MAX_ROWS 截断，这里只负责把数据交给它
                overlayManager.showHistory(items)
            }
        } finally {
            historyPanelLoading = false
        }
    }

    /** 历史面板加载中标志（防抖，避免连点弹出多个窗口） */
    private var historyPanelLoading = false

    /** 弹出预设指令选择面板并把选中项设为默认指令（悬浮球「选择指令」入口） */
    private suspend fun showPresetChooser() {
        val presets = loadPresets()
        withContextMain {
            // 「+ 新建指令」弹窗回调：保存 → 写入 DataStore 并刷新列表；取消 → 回到列表
            overlayManager.presetCreateListener =
                object : OverlayManager.PresetCreateListener {
                    override fun onSaved(
                        name: String,
                        prompt: String,
                    ) {
                        ServiceLocator.appScope.launch {
                            runCatching {
                                ServiceLocator.presetRepository.upsert(
                                    Preset(id = "", name = name, prompt = prompt),
                                )
                            }.onFailure { Log.w(TAG, "新建指令保存失败", it) }
                            // 重新打开选择面板：列表即时可见刚建的指令
                            showPresetChooser()
                        }
                    }

                    override fun onCancelled() {
                        ServiceLocator.appScope.launch { showPresetChooser() }
                    }
                }
            overlayManager.chooseFromPresets(
                options = presets.map { PresetOption.from(it) },
                allowCreate = true,
                onOpenManager = {
                    // 空列表时的「打开预设管理页」入口：跳转管理页新建
                    overlayManager.hideHistory()
                    startActivity(
                        Intent(this@FloatingService, com.screenshot.ai.ui.preset.PresetActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            ) { chosen ->
                // 选中即设为默认指令（单击悬浮球快捷截图分析使用）
                ServiceLocator.appScope.launch {
                    ServiceLocator.presetRepository.setDefault(chosen.id)
                    ServiceLocator.settingsRepository.setDefaultPreset(chosen.id)
                    withContextMain {
                        Toast
                            .makeText(
                                this@FloatingService,
                                getString(R.string.overlay_preset_default_set),
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                }
            }
        }
    }

    /** 用历史记录里的原图与指令重新提问 */
    private suspend fun runReAsk(item: HistoryItem) {
        val bitmap = ImageUtils.decodeSampled(item.imagePath, RE_ASK_MAX_EDGE)
        if (bitmap == null) {
            Log.w(TAG, "历史截图已不存在：${item.imagePath}")
            withContextMain {
                showResultMessage(
                    title = item.title,
                    message = getString(R.string.history_image_missing),
                )
            }
            return
        }
        try {
            runQuestion(
                bitmap = bitmap,
                preset = null,
                forcedPrompt = item.prompt,
                forcedTitle = item.title,
            )
        } finally {
            bitmap.recycle()
        }
    }

    // ---------------------------------------------------------------------
    // AI 主流程
    // ---------------------------------------------------------------------

    /**
     * 发起一次提问并流式渲染结果。
     *
     * @param bitmap       已拿到的截图（由调用方负责 [Bitmap.recycle]）
     * @param preset       指定指令；为 null 表示使用默认指令
     * @param forcedPrompt 强制使用的提问文本（历史「重新提问」场景）
     * @param forcedTitle  强制使用的标题
     */
    private suspend fun runQuestion(
        bitmap: Bitmap,
        preset: Preset?,
        forcedPrompt: String? = null,
        forcedTitle: String? = null,
    ) {
        // 一条新链路开始，先取消上一条，避免两个流式结果争抢同一个窗口
        cancelPreviousAiWork()
        // 新一轮对话：历史记录从零开始计，否则会更新到上一条记录上
        currentHistoryId = 0L
        currentImagePath = ""

        val settings = currentSettings()
        val provider = settings.provider
        val model = settings.model

        // 缺少 API Key 时直接给出可操作提示，不发起必然失败的请求
        val apiKey = ServiceLocator.cryptoRepository.getApiKey(provider.id)
        if (apiKey.isBlank()) {
            withContextMain {
                showResultMessage(
                    title = getString(R.string.overlay_result_title),
                    message = getString(R.string.overlay_no_api_key, provider.displayName),
                )
            }
            return
        }

        // 确定指令与标题（3.1.0 起无内置指令：列表为空时提示用户先新建）
        val resolvedPreset = preset ?: ServiceLocator.presetRepository.getDefault()
        if (resolvedPreset == null) {
            withContextMain {
                showResultMessage(
                    title = getString(R.string.overlay_result_title),
                    message = getString(R.string.overlay_no_preset),
                )
            }
            return
        }
        // 用户自建指令使用其自身 prompt，并追加「请用当前语言回答」的语言指令。
        val prompt = forcedPrompt ?: PromptTemplates.effectivePrompt(this, resolvedPreset)
        val title = forcedTitle ?: resolvedPreset.displayName(this)

        // 落盘截图：即使 AI 请求失败，历史里也留得下这张图
        val imagePath =
            runCatching {
                ServiceLocator.imageStore.saveScreenshot(bitmap, SCREENSHOT_QUALITY)
            }.getOrElse { error ->
                Log.w(TAG, "截图保存失败", error)
                ""
            }

        // 组织提示信息（3.1.0 起无内置指令，无默认提示）
        val tips = mutableListOf<String>()

        val session =
            ChatSession(
                provider = provider,
                model = model,
                baseUrl = resolveBaseUrl(provider.id),
                apiKey = apiKey,
                // 系统提示词同样与界面语言解耦：语言约束由 PromptTemplates 在末尾显式给出
                systemPrompt = PromptTemplates.SYSTEM_PROMPT,
            )
        currentSession = session
        currentTitle = title
        currentPrompt = prompt

        if (session.isVisionAvailable) {
            val base64 =
                ImageUtils.toBase64(
                    ImageUtils.compressForUpload(bitmap, uploadMaxEdge(settings)),
                )
            session.start(prompt, base64)
            withContextMain { showResultMessage(title, "", streaming = true, tips = tips) }
            runStream(session, title, prompt, imagePath)
        } else {
            // 模型不支持图片：先本地 OCR 提取文字，再把文字当纯文本提问，
            // 这样换成纯文本模型（或用户自定义了非视觉模型）依然可用
            tips += getString(R.string.overlay_ocr_fallback)
            val recognized = runCatching { OcrUtils.recognizeText(bitmap) }.getOrDefault("")
            // OCR 降级路径同样要带上语言指令，否则非视觉模型的回复语言会失控
            val ocrPrompt =
                if (recognized.isBlank()) {
                    prompt
                } else {
                    PromptTemplates.withOcrText(prompt, recognized)
                }
            session.start(ocrPrompt, null)
            currentPrompt = ocrPrompt
            withContextMain { showResultMessage(title, "", streaming = true, tips = tips) }
            runStream(session, title, ocrPrompt, imagePath)
        }
    }

    /** 追问：复用同一会话与同一张截图 */
    private suspend fun runFollowUp(prompt: String) {
        val session = currentSession
        if (session == null) {
            Log.w(TAG, "会话已不存在，无法追问")
            return
        }
        cancelPreviousAiWork()
        currentPrompt = prompt
        withContextMain { showResultMessage(currentTitle, "", streaming = true) }
        val callback = buildCallback(session, currentTitle, prompt, currentImagePath)
        runCatching { session.followUp(prompt, callback) }
            .onFailure { handleStreamFailure(it, callback.streamedText()) }
    }

    /** 重新生成：丢弃上一条回复后重发，保留原图与用户指令 */
    private suspend fun runRegenerate() {
        val session = currentSession
        if (session == null) {
            Log.w(TAG, "会话已不存在，无法重新生成")
            return
        }
        cancelPreviousAiWork()
        withContextMain { showResultMessage(currentTitle, "", streaming = true) }
        val callback = buildCallback(session, currentTitle, currentPrompt, currentImagePath)
        runCatching { session.regenerate(callback) }
            .onFailure { handleStreamFailure(it, callback.streamedText()) }
    }

    /** 执行首轮流式输出（[ChatSession.start] 已写入用户消息，这里只需 send） */
    private suspend fun runStream(
        session: ChatSession,
        title: String,
        prompt: String,
        imagePath: String,
    ) {
        val callback = buildCallback(session, title, prompt, imagePath)
        // 成功路径由回调的 onComplete 负责收尾；异常路径由 handleStreamFailure 负责展示错误，
        // 两者互斥，因此这里不需要（也不能）再补一次渲染——否则会把错误提示覆盖掉。
        runCatching { session.send(callback) }
            .onFailure { handleStreamFailure(it, callback.streamedText()) }
    }

    /**
     * 构建流式回调。
     *
     * 三件事必须在回调里完成：
     * 1. 增量文本回传 UI（切主线程，传全量快照）；
     * 2. 完成后把完整回复写回 [ChatSession] 上下文，追问时模型才能看到自己说过什么；
     * 3. 完成后写入 / 更新历史记录。
     *
     * 回调实现同时充当「已收到文本」的累积器，异常路径可以直接从它取出部分内容。
     */
    private fun buildCallback(
        session: ChatSession,
        title: String,
        prompt: String,
        imagePath: String,
    ): StreamingCallback =
        StreamingCallback(
            onRender = { fullText, streaming, errorText ->
                // 该回调由 OkHttp 读线程触发，本身不是挂起上下文，因此用 appScope.launch
                // 起一个协程后再切主线程更新 UI，避免阻塞流式读取。
                ServiceLocator.appScope.launch {
                    withContextMain {
                        overlayManager.showResult(
                            ResultUiState(
                                title = title,
                                content = fullText,
                                isStreaming = streaming,
                                errorText = errorText,
                                // 一轮输出完成后才允许追问：流式期间发新请求会污染上下文
                                canFollowUp = !streaming,
                            ),
                        )
                    }
                }
            },
            // 注入「异常 → 当前语言文案」的转换，StreamingCallback 本身不碰资源
            localizeError = { error -> AiErrorMessages.localize(this, error) },
        ).also { callback ->
            callback.onFinished = { reply ->
                // 写回上下文：追问阶段模型需要看到自己上一轮的回复
                session.appendAssistant(reply)
                // 落库走独立的 persistScope，避免被 aiJob 的取消连带打断（详见 persistScope 的说明）
                persistScope.launch {
                    persistHistory(title, prompt, imagePath, reply)
                }
            }
        }

    /** 请求抛异常（非回调路径）时的统一处理 */
    private fun handleStreamFailure(
        error: Throwable,
        partialText: String,
    ) {
        // 错误文案按当前界面语言渲染：领域层只给结构化异常，不写死某种语言
        val message = AiErrorMessages.localize(this, error)
        Log.w(TAG, "AI 请求异常：$message", error)
        // 同样不是挂起上下文：起协程后再切主线程渲染错误态
        ServiceLocator.appScope.launch {
            withContextMain {
                overlayManager.showResult(
                    ResultUiState(
                        title = currentTitle,
                        content = partialText,
                        isStreaming = false,
                        errorText = message,
                        // 出错后仍允许追问：用户可以换个说法继续，不必重新截图
                        canFollowUp = true,
                    ),
                )
            }
        }
        // 出错路径同样要留痕：用户能看到部分回复，历史里也应该有
        persistScope.launch {
            persistHistory(currentTitle, currentPrompt, currentImagePath, partialText)
        }
    }

    /**
     * 写入或更新历史记录。
     *
     * 同一轮对话只保留一条记录：首次插入拿 id，之后的追问 / 重新生成用 updateReply 回写，
     * 否则用户问三句就会在历史里看到三条重复的截图。
     */
    private suspend fun persistHistory(
        title: String,
        prompt: String,
        imagePath: String,
        reply: String,
    ) {
        runCatching {
            if (imagePath.isNotBlank()) currentImagePath = imagePath
            val existingId = currentHistoryId
            if (existingId != 0L) {
                ServiceLocator.historyRepository.updateReply(existingId, reply)
            } else {
                val settings = currentSettings()
                currentHistoryId =
                    ServiceLocator.historyRepository.insert(
                        HistoryItem(
                            timestamp = System.currentTimeMillis(),
                            imagePath = currentImagePath,
                            presetName = title,
                            providerId = settings.providerId,
                            modelId = settings.modelId,
                            prompt = prompt,
                            aiReply = reply,
                        ),
                    )
            }
        }.onFailure { Log.w(TAG, "写入历史失败", it) }
    }

    // ---------------------------------------------------------------------
    // 截图
    // ---------------------------------------------------------------------

    /**
     * 隐藏悬浮窗 -> 截屏 -> 恢复显示。
     *
     * 必须先隐藏再截屏：悬浮球与面板都是 TYPE_APPLICATION_OVERLAY 窗口，
     * takeScreenshot 会把它们一起拍进画面。隐藏后还要等待 [HIDE_BEFORE_CAPTURE_MS]：
     * View 由 VISIBLE 变为 INVISIBLE 后需要跨过一次 VSYNC 才会真正从合成层消失，
     * 立刻截屏仍可能拍到它。
     *
     * @return 截图位图；失败时返回 null，并已在结果窗口给出可操作提示
     */
    private suspend fun captureScreenOrReport(): Bitmap? {
        if (!ScreenCaptureService.isRunning()) {
            Log.w(TAG, "无障碍截屏服务未运行")
            withContextMain {
                showResultMessage(
                    title = getString(R.string.overlay_capture_preview_title),
                    message = getString(R.string.overlay_capture_fail),
                )
            }
            return null
        }
        withContextMain { overlayManager.hideForCapture() }
        delay(HIDE_BEFORE_CAPTURE_MS)
        val bitmap =
            try {
                ScreenCaptureService.capture()
            } finally {
                // 无论截屏成功与否都必须恢复悬浮窗，否则用户会以为悬浮球消失了
                withContextMain { overlayManager.restoreAfterCapture() }
            }
        if (bitmap == null) {
            // 区分两类失败：服务未运行（入口已拦截）vs 服务在运行但截屏失败——
            // 后者提示重试而不是让用户去检查一个本来就开着的服务
            withContextMain {
                showResultMessage(
                    title = getString(R.string.overlay_capture_preview_title),
                    message =
                        if (ScreenCaptureService.isRunning()) {
                            getString(R.string.overlay_capture_fail_running)
                        } else {
                            getString(R.string.overlay_capture_fail)
                        },
                )
            }
        }
        return bitmap
    }

    // ---------------------------------------------------------------------
    // 工具方法
    // ---------------------------------------------------------------------

    /**
     * 启动一条 AI 链路，并把它登记为「当前链路」。
     *
     * 所有入口都通过它启动，保证任意时刻只有一个 [aiJob]，
     * 新链路会覆盖旧链路的登记（旧链路由链路内部的 [cancelPreviousAiWork] 取消）。
     */
    private fun startAiFlow(block: suspend CoroutineScope.() -> Unit) {
        aiJob = ServiceLocator.appScope.launch(block = block)
    }

    /**
     * 取消上一条 AI 链路。
     *
     * 必须比对自己所在的 Job：链路入口是通过 [startAiFlow] 启动的，
     * 而 [runQuestion] 等方法运行在该 Job 内部，如果无脑取消 [aiJob]，
     * 新链路会把自己取消掉，表现为「点击后毫无反应」。
     */
    private suspend fun cancelPreviousAiWork() {
        val current = currentCoroutineContext()[Job]
        val previous = aiJob
        if (previous != null && previous !== current) {
            previous.cancel()
        }
        aiJob = current
    }

    /**
     * 在结果窗口展示一条纯文本消息（错误 / 提示 / 等待占位）。
     *
     * @param streaming 为 true 时表示「占位等待中」，正文为空并显示流式光标
     */
    /**
     * 结果窗口「仅消息」模式（截图失败 / 缺少 API Key / 无预设等）。
     *
     * 内容走 errorText（灰色居中、24dp padding），正文区自动隐藏（无留白），
     * 复制/重新生成置灰（只保留关闭），追问行不显示。
     */
    private fun showResultMessage(
        title: String,
        message: String,
        streaming: Boolean = false,
        tips: List<String> = emptyList(),
    ) {
        overlayManager.showResult(
            ResultUiState(
                title = title,
                content = "",
                isStreaming = streaming,
                errorText = message.ifBlank { null },
                canFollowUp = false,
                statusTip = tips.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            ),
        )
    }

    /** 读取当前配置（一次性快照，避免长期持有 Flow 收集） */
    private suspend fun currentSettings(): AppSettings =
        runCatching {
            withTimeoutOrNull(SETTINGS_TIMEOUT_MS) {
                ServiceLocator.settingsRepository.settings.first()
            }
        }.getOrNull() ?: AppSettings()

    /** 取预设列表（3.1.0 起无内置指令：为空即真为空，由调用方处理空态） */
    private suspend fun loadPresets(): List<Preset> =
        runCatching {
            withTimeoutOrNull(PRESET_TIMEOUT_MS) {
                ServiceLocator.presetRepository.observeAll().first()
            }
        }.getOrNull().orEmpty()

    /** 接口地址：用户自定义优先，未设置时回退到 Provider 默认地址 */
    private fun resolveBaseUrl(providerId: String): String {
        val custom = ServiceLocator.cryptoRepository.getBaseUrl(providerId)
        if (custom.isNotBlank()) return custom
        return AiProviderRegistry.find(providerId)?.defaultBaseUrl.orEmpty()
    }

    /** 上传图片的最长边：低质量模式压到 512px，显著节省 token */
    private fun uploadMaxEdge(settings: AppSettings): Int =
        if (settings.lowQuality) LOW_QUALITY_MAX_EDGE else DEFAULT_MAX_EDGE

    /** 回收预览位图（重拍 / 关闭面板时调用） */
    private fun releasePreviewBitmap() {
        previewBitmap?.recycle()
        previewBitmap = null
    }

    /** 回收待确认截图 */
    private fun recyclePendingCapture() {
        pendingCapture?.recycle()
        pendingCapture = null
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
    }

    /** 把 UI 更新切回主线程（WindowManager 与 View 操作都要求主线程） */
    private suspend fun withContextMain(block: () -> Unit) {
        if (isMainThread()) {
            block()
        } else {
            withContext(Dispatchers.Main) { block() }
        }
    }

    /** 当前是否已在主线程：已经在主线程时直接执行，省掉一次调度往返 */
    private fun isMainThread(): Boolean = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

    /** 处理外部投递的「重新提问」请求（服务存活时调用） */
    private fun requestReAsk(item: HistoryItem) {
        startAiFlow { runReAsk(item) }
    }

    /**
     * 流式回调实现。
     *
     * 独立成类而不是匿名对象：需要在「请求抛异常的路径」上用 [streamedText] 取回已收到的内容，
     * 匿名对象无法被外部引用。
     */
    private class StreamingCallback(
        private val onRender: (fullText: String, streaming: Boolean, errorText: String?) -> Unit,
        private val localizeError: (Throwable) -> String,
    ) : StreamCallback {
        private val builder = StringBuilder()

        /** 上一次推送给 UI 的时间戳；用于给高频分片做节流 */
        private var lastRenderAt = 0L

        /** 完成回调；由 [buildCallback] 注入（写回上下文 + 落库） */
        var onFinished: ((reply: String) -> Unit)? = null

        /** 已收到的全部文本 */
        fun streamedText(): String = builder.toString()

        override fun onStart() = Unit

        override fun onChunk(text: String) {
            builder.append(text)
            // 部分 Provider 会按字推送，逐字刷新会让主线程每毫秒重建一次 TextView 文本；
            // 这里按时间节流，既保证观感上的「逐字出现」，又不会把主线程拖垮。
            val now = System.currentTimeMillis()
            if (now - lastRenderAt >= RENDER_INTERVAL_MS) {
                lastRenderAt = now
                onRender(builder.toString(), true, null)
            }
        }

        override fun onError(
            error: Throwable,
            partialText: String,
        ) {
            // 优先使用回调自带的部分文本：某些实现会在出错时补发完整片段
            val content = if (partialText.isNotEmpty()) partialText else builder.toString()
            // 错误文案必须按当前界面语言渲染，因此把「异常 → 文案」的转换交给
            // 持有 Context 的调用方注入，本类保持与 Android 资源无关。
            val message = localizeError(error)
            onRender(content, false, message)
        }

        override fun onComplete() {
            val reply = builder.toString()
            // 无论前面是否被节流，结束都必须渲染最终全文（末尾被跳过的分片在这里补齐）
            onRender(reply, false, null)
            onFinished?.invoke(reply)
        }

        companion object {
            /** 流式渲染的最小间隔：约 12fps，肉眼已足够流畅 */
            private const val RENDER_INTERVAL_MS = 80L
        }
    }

    // ---------------------------------------------------------------------
    // 伴生对象：外部启动 / 停止 / 重新提问
    // ---------------------------------------------------------------------

    companion object {
        private const val TAG = "FloatingService"

        /** 通知渠道 id */
        private const val CHANNEL_ID = "floating_toolbar"

        /** 前台服务通知 id */
        private const val NOTIFICATION_ID = 1001

        /** 通知栏「停止」动作 */
        const val ACTION_STOP = "com.screenshot.ai.action.STOP_FLOATING"

        private const val REQUEST_CONTENT = 2001
        private const val REQUEST_STOP = 2002

        /** 隐藏悬浮窗后等待的时间：必须跨过一次 VSYNC，否则截屏里仍会带上悬浮球 */
        private const val HIDE_BEFORE_CAPTURE_MS = 120L

        /** JPEG 落盘质量：截图以文字为主，80 足够清晰且体积明显更小 */
        private const val SCREENSHOT_QUALITY = 80

        /** 预览图最长边 */
        private const val PREVIEW_MAX_EDGE = 720

        /** 上传图片默认最长边 */
        private const val DEFAULT_MAX_EDGE = 1024

        /** 低质量模式的上传最长边 */
        private const val LOW_QUALITY_MAX_EDGE = 512

        /** 历史「重新提问」时解码原图的最长边 */
        private const val RE_ASK_MAX_EDGE = 1024

        /** 剪贴板标签，便于用户在剪贴板管理器里辨认来源 */
        private const val CLIP_LABEL = "screenshot-ai"

        private const val SETTINGS_TIMEOUT_MS = 3_000L
        private const val PRESET_TIMEOUT_MS = 3_000L
        private const val HISTORY_TIMEOUT_MS = 5_000L

        private const val RE_ASK_READY_TIMEOUT_MS = 3_000L
        private const val RE_ASK_POLL_MS = 50L

        /** 服务实例；[isRunning] 与 [reAsk] 都依赖它 */
        @Volatile
        private var runningInstance: FloatingService? = null

        /** 悬浮服务是否正在运行（主界面开关据此判断是否需要复位） */
        fun isRunning(): Boolean = runningInstance != null

        /** 启动悬浮工具栏（主界面开关、开机自启都走这里） */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FloatingService::class.java),
                )
            }.onFailure { Log.e(TAG, "启动悬浮服务失败", it) }
        }

        /** 停止悬浮工具栏 */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, FloatingService::class.java))
            }.onFailure { Log.e(TAG, "停止悬浮服务失败", it) }
        }

        /**
         * 设置页避让：展开「模型管理」卡片时临时隐藏悬浮球，
         * 避免它遮住 API Key 输入框与按钮；页面退出时由调用方传 false 恢复。
         * 服务未运行时为空操作。
         */
        fun setBubbleAvoidance(hidden: Boolean) {
            runningInstance?.overlayManager?.setAvoidanceHidden(hidden)
        }

        /** 语言切换后刷新悬浮窗按钮文案（服务未运行时为空操作） */
        fun refreshOverlayLanguage(context: android.content.Context) {
            runningInstance?.overlayManager?.refreshOverlayLanguage(context)
        }

        /**
         * 从历史记录重新提问。
         *
         * 历史页调用时服务可能并未运行（用户关掉了悬浮球），因此先启动服务，
         * 再等实例登记完成后把请求投递给它；等待有超时，避免请求被无限期挂起。
         */
        fun reAsk(
            context: Context,
            item: HistoryItem,
        ) {
            ServiceLocator.init(context)
            ServiceLocator.appScope.launch {
                if (runningInstance == null) {
                    Log.i(TAG, "悬浮服务未运行，先启动再重新提问")
                    start(context)
                    val ready =
                        withTimeoutOrNull(RE_ASK_READY_TIMEOUT_MS) {
                            while (runningInstance == null) {
                                delay(RE_ASK_POLL_MS)
                            }
                            true
                        }
                    if (ready == null) {
                        Log.w(TAG, "等待悬浮服务启动超时，放弃本次重新提问")
                        return@launch
                    }
                }
                runningInstance?.requestReAsk(item)
            }
        }
    }
}

/**
 * 把面板条目还原成可执行的预设。
 *
 * 只补齐提问所需字段：icon / isDefault / builtin 用默认值即可，
 * 这条路径只读取 name 与 prompt，不会把结果写回仓储。
 */
private fun PresetOption.toPresetForRun(): Preset = Preset(id = id, name = name, prompt = prompt)
