package com.screenshot.ai.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import com.screenshot.ai.R
import com.screenshot.ai.ServiceLocator
import com.screenshot.ai.data.model.HistoryItem
import com.screenshot.ai.data.model.Preset
import com.screenshot.ai.data.prefs.AppSettings
import com.screenshot.ai.overlay.OverlayActionPanelView
import com.screenshot.ai.overlay.OverlayBubbleContainerView
import com.screenshot.ai.overlay.OverlayBubbleView
import com.screenshot.ai.overlay.OverlayCapturePreviewView
import com.screenshot.ai.overlay.OverlayHistoryPanelView
import com.screenshot.ai.overlay.OverlayPresetCreateView
import com.screenshot.ai.overlay.OverlayResultWindowView
import com.screenshot.ai.overlay.WindowGestureBinder
import com.screenshot.ai.util.FloatingDragListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 窗口 flag 常量（WindowManager.LayoutParams 上的静态成员直接导入会更易读） */
private const val FLAG_NOT_FOCUSABLE: Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

/**
 * AI 回复窗口的状态快照。
 *
 * 用不可变数据类而不是「往 View 上一点点塞」的原因：
 * 流式输出期间状态变化非常频繁，把「标题 / 正文 / 是否输出中 / 错误 / 能否追问」
 * 收在一个快照里，UI 渲染就是一次纯函数式的 apply，不会出现「错误已清除但光标还在转」这类中间态。
 *
 * @param title        标题（通常是预设指令名）
 * @param content      正文全文（流式时为「已收到的全部增量」，不是单次增量）
 * @param isStreaming  是否仍在接收数据；为 true 时正文末尾显示光标
 * @param errorText    错误提示；null 表示无错误
 * @param canFollowUp  是否允许追问（**一轮输出完成后**才为 true）
 * @param statusTip    附加提示（默认指令提示 / OCR 降级提示等）
 */
data class ResultUiState(
    val title: String,
    val content: String,
    val isStreaming: Boolean,
    val errorText: String? = null,
    val canFollowUp: Boolean = false,
    val statusTip: String? = null,
)

/**
 * 面板上与「预设指令」相关的展示数据。
 *
 * 刻意不直接用 [Preset]：面板只需要三个字段，暴露整个实体反而会让
 * 「改预设模型就要改面板」这种隐性耦合出现。同时保留 [id]，
 * 因为「设为默认」「回写历史」都需要回查仓储。
 */
data class PresetOption(
    val id: String,
    val name: String,
    val prompt: String,
) {
    companion object {
        /** 由实体转换；保留 id 以便回写仓储 */
        fun from(preset: Preset): PresetOption =
            PresetOption(id = preset.id, name = preset.name, prompt = preset.prompt)
    }
}

/**
 * 反向转换：面板条目 -> 实体。
 *
 * 只补齐展示所需字段（icon / isDefault / builtin 用默认值），
 * 用于「选择指令」场景——该场景只读取 name 与 prompt，不会把结果写回仓储。
 */
private fun PresetOption.toPreset(): Preset = Preset(id = id, name = name, prompt = prompt)

/**
 * 悬浮窗总控：唯一直接操作 [WindowManager] 的类。
 *
 * 职责：
 * - 悬浮球的添加 / 移除 / 定位 / 贴边半隐藏；
 * - 各类面板（操作面板 / 结果窗口 / 历史 / 录入 / 截图预览）的显示与互斥；
 * - 截屏前的「隐藏自身」，避免悬浮球被拍进截图里。
 *
 * 为什么所有窗口都集中在这里：
 * TYPE_APPLICATION_OVERLAY 窗口没有父容器，也没有 Activity 的生命周期兜底，
 * 一旦分散到多个类里各自 addView，就会出现「面板已经移除但悬浮球还记着它」的脏状态。
 * 集中管理后，[hideAllPanels] 是唯一的状态回收入口。
 *
 * 线程约束：所有公开方法都必须在主线程调用（WindowManager 的增删改都要求主线程）。
 */
class OverlayManager(
    private val context: Context,
) {
    /**
     * 悬浮窗交互回调。
     *
     * 只定义「用户做了什么」，不定义「业务怎么跑」：
     * 具体链路（截图 → 压缩 → 请求 → 落库）都在 [FloatingService] 里，
     * 这样 OverlayManager 保持为纯 UI 控制器，可以被独立测试或替换。
     */
    interface Listener {
        /** 单击悬浮球（快捷模式：直接截图提问） */
        fun onBubbleTap()

        /** 操作面板：截图（走预览确认） */
        fun onScreenshot()

        /** 操作面板：录入预设指令 */
        fun onInputPreset()

        /** 操作面板：查看历史 */
        fun onHistory()

        /** 操作面板：打开设置 */
        fun onSettings()

        /** 结果窗口：关闭（调用方需要取消进行中的请求） */
        fun onResultClose()

        /** 结果窗口：重新生成 */
        fun onResultRegenerate()

        /** 结果窗口：追问 */
        fun onResultFollowUp(prompt: String)

        /** 历史面板：复制 */
        fun onHistoryCopy(item: HistoryItem)

        /** 历史面板：删除 */
        fun onHistoryDelete(item: HistoryItem)

        /** 历史面板：重新提问 */
        fun onHistoryReAsk(item: HistoryItem)

        /** 截图预览：确认 */
        fun onCaptureConfirm()

        /** 截图预览：重拍 */
        fun onCaptureRetry()
    }

    var listener: Listener? = null

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** 当前生效的配置快照；默认值取自 [AppSettings] 的默认实现 */
    private var settings: AppSettings = AppSettings()

    // ---------------------------------------------------------------------
    // 悬浮球（球 + 四按钮面板共用的单窗口容器）
    // ---------------------------------------------------------------------

    /** 悬浮球与操作面板的宿主容器：WindowManager 只 add 这一个窗口 */
    private var containerView: OverlayBubbleContainerView? = null

    /** 容器窗口参数：窗口位置 = 容器左上角（球/面板的相对位置由容器内部布局决定） */
    private var bubbleParams: WindowManager.LayoutParams? = null

    /** 径向菜单（独立窗口）参数；非空表示菜单窗口已加入 */
    private var radialParams: WindowManager.LayoutParams? = null

    private var bubbleDragListener: FloatingDragListener? = null

    /** 气泡拖拽的触点偏移（按下时手指相对气泡左上角） */
    private var bubbleTouchOffsetX = 0
    private var bubbleTouchOffsetY = 0

    /** 是否正在拖动：拖动期间忽略配置回流，避免与手指抢位置 */
    private var dragging = false

    /** 标记 onDragStart 是否已提供偏移（旧版监听器没有该回调时的兼容标志） */
    private var dragListenerNeverStarted = true

    /**
     * 贴边半隐藏时气泡的横向平移量（px）。
     *
     * 必须与「窗口 x 坐标」区分开：窗口坐标始终是气泡完整显示的坐标，
     * 半隐藏只改变 View 自身的 translationX。恢复显示时（[OverlayBubbleView.setVisibility]）
     * translationX 会被清零，所以这里要单独记住，[showBubble] 时重新应用。
     */
    private var snapOffsetPx = 0

    /** 截屏前气泡是否本来就不可见：如果本来就是隐藏的，截屏后不应该把它显示出来 */
    private var bubbleWasVisibleBeforeCapture = false

    /** 设置页避让模式：为 true 时悬浮球被临时隐藏（见 setAvoidanceHidden） */
    private var avoidanceHidden = false

    /** 当前贴边状态（用于触摸按下时恢复完整显示） */
    private var snapped = false

    // ---------------------------------------------------------------------
    // 面板
    // ---------------------------------------------------------------------

    // 操作面板已并入悬浮球容器（见 containerView），不再单独 addView；
    // 这里只保留「是否展开」的读取入口，收起/展开逻辑由 toggleActionPanel 委托给容器。
    private val actionPanelView: OverlayActionPanelView? get() = containerView?.panel

    private var resultView: OverlayResultWindowView? = null
    private var resultParams: WindowManager.LayoutParams? = null

    private var historyView: OverlayHistoryPanelView? = null
    private var historyParams: WindowManager.LayoutParams? = null

    private var previewView: OverlayCapturePreviewView? = null
    private var previewParams: WindowManager.LayoutParams? = null

    /** 预览收起动画进行中（单飞标志，防止重复移除窗口） */
    private var previewHiding = false

    /** 配置监听任务；用一个内部的 [CoroutineScope] 承载，[dispose] 时取消 */
    private var settingsJob: Job? = null
    private var scope: CoroutineScope? = null

    init {
        bubbleDragListener = buildBubbleDragListener()
    }

    // ---------------------------------------------------------------------
    // 悬浮球生命周期
    // ---------------------------------------------------------------------

    /**
     * 显示悬浮球。
     *
     * 前置条件：必须已获得「显示在其他应用上层」权限，否则 addView 会抛
     * `WindowManager.BadTokenException`。权限引导属于 UI 层职责（主界面开关），
     * 这里只做防御性检查并记录日志。
     */
    fun showBubble() {
        if (containerView != null) {
            Log.d(TAG, "悬浮球已存在，忽略重复显示")
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.e(TAG, "缺少悬浮窗权限，无法显示悬浮球；请在主界面引导用户授权")
            return
        }
        // 悬浮球窗口只承载球本体；径向菜单是独立窗口（见 toggleActionPanel）
        val container = OverlayBubbleContainerView(context)
        container.bubble.applyAlpha(settings.floatingAlpha)
        container.bubble.setOnTouchListener(bubbleDragListener)
        container.panel.onOutsideTouch = { hideActionPanel() }
        container.panel.listener =
            object : OverlayActionPanelView.Listener {
                override fun onScreenshot() = listener?.onScreenshot() ?: Unit

                override fun onInputPreset() = listener?.onInputPreset() ?: Unit

                override fun onHistory() = listener?.onHistory() ?: Unit

                override fun onSettings() = listener?.onSettings() ?: Unit
            }

        val params = buildBubbleParams()
        bubbleParams = params
        containerView = container
        addViewSafely(container, params, "悬浮球容器")
    }

    /** 移除悬浮球；同时回收所有面板（含径向菜单），避免留下无主的窗口 */
    fun hideBubble() {
        hideAllPanels()
        containerView?.let { container ->
            container.bubble.setOnTouchListener(null)
            removeViewSafely(container, "悬浮球容器")
        }
        containerView = null
        bubbleParams = null
        snapped = false
        snapOffsetPx = 0
    }

    /**
     * 订阅配置变化并实时应用（透明度 / 尺寸 / 贴边开关）。
     *
     * @param scope 承载订阅的协程作用域，通常传入 `ServiceLocator.appScope`；
     *              Flow 的 collect 会切回主线程执行 UI 更新（见实现中的 withContext 说明）。
     */
    fun observeSettings(
        scope: CoroutineScope,
        settingsFlow: Flow<AppSettings>,
    ) {
        this.scope = scope
        settingsJob?.cancel()
        settingsJob =
            scope.launch {
                // 配置 Flow 可能从后台线程发射（DataStore 的 IO 调度），
                // 而 applySettingsOnMain 内部要动 View（applyAlpha 会改 View 状态）。
                // 用 flowOn(Main) 强制把「收集」切到主线程，杜绝
                // CalledFromWrongThreadException（ViewRootImpl 只允许创建它的线程碰视图）。
                settingsFlow
                    .flowOn(Dispatchers.Main)
                    .collect { latest ->
                        // 兜底：flowOn 只约束上游，collect 本身仍在 launch 的上下文；
                        // 因此这里再做一次显式断言，任何非主线程到达都丢回主线程执行。
                        runOnMain { applySettingsOnMain(latest) }
                    }
            }
    }

    /** 释放资源：取消配置订阅并移除全部窗口 */
    fun dispose() {
        settingsJob?.cancel()
        settingsJob = null
        scope = null
        hideBubble()
    }

    /**
     * 应用最新配置。
     *
     * 只处理会影响悬浮球外观与行为的字段（透明度 / 尺寸 / 贴边），
     * 位置字段刻意不参与配置回流：用户拖动时位置会频繁变化，
     * 若也走配置回流，会出现「刚拖到新位置又被旧配置拉回去」的抖动。
     *
     * **线程约束：本方法内部会 setLayoutParams，必须在主线程执行。**
     * 调用点已通过 observeSettings 的 runOnMain 兜底，这里再次用 [runOnMain] 包裹，
     * 这样即使未来有人从后台线程直接调用它也不会崩。
     */
    private fun applySettingsOnMain(latest: AppSettings) {
        runOnMain {
            settings = latest
            val container = containerView ?: return@runOnMain
            val bubble = container.bubble
            if (dragging) return@runOnMain
            // 径向面板展开/收起动画期间跳过配置回流：
            // 动画只动渲染属性，此时不应有任何 UI 更新触发二次布局（防抽搐）
            if (isActionPanelVisible()) return@runOnMain
            bubble.applyAlpha(latest.floatingAlpha)
            if (!latest.snapToEdge) {
                // 关掉贴边后要立刻把半隐藏的气泡拉回完整显示，否则用户会以为按钮消失了
                restoreFromSnapIfNeeded()
            }
        }
    }

    /**
     * 语言切换后刷新悬浮窗文案（主线程执行）。
     * @param localized 已应用新语言的 Context（由调用方 Activity 传入）
     */
    fun refreshOverlayLanguage(localized: android.content.Context) {
        runOnMain {
            containerView?.panel?.refreshLabels(localized)
        }
    }

    /**
     * 设置页避让：展开输入卡片时隐藏悬浮球，避免遮挡输入框与按钮。
     *
     * 与 hideForCapture 共用同一套 INVISIBLE 机制（窗口不销毁、位置状态不丢），
     * 因此从设置页返回后可以原样恢复。所有窗口操作经 runOnMain 保证主线程执行。
     */
    fun setAvoidanceHidden(hidden: Boolean) {
        runOnMain {
            if (avoidanceHidden == hidden) return@runOnMain
            avoidanceHidden = hidden
            if (hidden) {
                setVisibilitySafely(containerView, View.INVISIBLE, bubbleParams, "设置页避让：隐藏悬浮球")
            } else {
                setVisibilitySafely(containerView, View.VISIBLE, bubbleParams, "设置页避让：恢复悬浮球")
            }
        }
    }

    /**
     * 截屏前隐藏悬浮球与所有面板。
     *
     * 悬浮球本身就是一个 TYPE_APPLICATION_OVERLAY 窗口，不隐藏的话会被系统截进画面里
     * （takeScreenshot 会包含所有 overlay 图层）。所有面板同理。
     *
     * 用 [View.INVISIBLE] 而不是 removeView：移除会销毁 View，截屏结束后重建会导致
     * 悬浮球位置、面板输入内容全部丢失。
     */
    fun hideForCapture() {
        bubbleWasVisibleBeforeCapture = containerView?.visibility == View.VISIBLE
        // 隐藏整个容器即可同时隐藏球与已展开的四按钮面板（它们在同一窗口里）
        setVisibilitySafely(containerView, View.INVISIBLE, bubbleParams, "隐藏悬浮球")
        // 其它独立面板（结果 / 历史 / 预览 / 径向菜单）仍各自是独立窗口，逐一隐藏
        listOf<Pair<View?, WindowManager.LayoutParams?>>(
            resultView to resultParams,
            historyView to historyParams,
            previewView to previewParams,
            containerView?.panel to radialParams,
        ).forEach { (view, params) -> setVisibilitySafely(view, View.INVISIBLE, params, "隐藏面板") }
    }

    /**
     * 截屏后恢复显示。
     *
     * 注意：[hideForCapture] 只是把容器置为 INVISIBLE，窗口本身仍在 WindowManager 中，
     * 因此截屏时不会有残留的窗口边框或阴影被拍进去。
     */
    fun restoreAfterCapture() {
        setVisibilitySafely(containerView, View.VISIBLE, bubbleParams, "恢复悬浮球")
        if (!bubbleWasVisibleBeforeCapture) {
            // 截屏前本来就不可见（例如服务正在退出），不要擅自显示
            setVisibilitySafely(containerView, View.INVISIBLE, bubbleParams, "保持悬浮球隐藏")
        }
        restorePanelsAfterCapture()
    }

    /**
     * 恢复面板显示。
     *
     * 只恢复「本来就在显示」的面板：这里用「参数非空即认为该面板处于打开状态」判断，
     * 因为所有面板在 [hideAllPanels] 中会把参数一并置空。
     */
    private fun restorePanelsAfterCapture() {
        listOf<Pair<View?, WindowManager.LayoutParams?>>(
            resultView to resultParams,
            historyView to historyParams,
            previewView to previewParams,
            containerView?.panel to radialParams,
        ).forEach { (view, params) ->
            if (view != null && params != null) {
                setVisibilitySafely(view, View.VISIBLE, params, "恢复面板")
            }
        }
    }

    // ---------------------------------------------------------------------
    // 面板：操作面板
    // ---------------------------------------------------------------------

    /**
     * 长按悬浮球：切换径向菜单（独立固定尺寸窗口）。
     *
     * **重写版（彻底解决「四个按钮只剩一个」）**：
     * - 菜单是**独立窗口**：固定 220dp 正方形，以悬浮球屏幕中心为窗口中心 addView；
     * - 悬浮球窗口本身（56dp）**尺寸与坐标全程不变**——不再有任何「窗口放大」
     *   与拖拽/贴边的交互，按钮坐标在构造时写死，数学上不可能飞出窗口；
     * - 展开/收起只动按钮的 scale/alpha（transform-only）。
     */
    fun toggleActionPanel() {
        val container = containerView ?: return
        val panel = container.panel
        if (isActionPanelVisible()) {
            hideActionPanel()
            return
        }
        hideOtherPanels()
        val bubble = bubbleParams ?: return
        val bubblePx = bubbleSizePx()
        val centerX = bubble.x + bubblePx / 2
        val centerY = bubble.y + bubblePx / 2
        val radialSize = dpToPx(OverlayActionPanelView.RADIAL_SIZE_DP.toFloat())

        val params =
            WindowManager
                .LayoutParams(
                    radialSize,
                    radialSize,
                    overlayType(),
                    PANEL_FLAGS or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = centerX - radialSize / 2
                    y = centerY - radialSize / 2
                }
        radialParams = params
        addViewSafely(panel, params, "径向菜单")
        panel.showRadial(animate = true)
    }

    /** 关闭径向菜单（按钮缩小淡出后移除窗口） */
    fun hideActionPanel() {
        val panel = containerView?.panel ?: return
        panel.hideRadial(animate = true) {
            radialParams?.let { removeViewSafely(panel, "径向菜单") }
            radialParams = null
        }
    }

    /** 四周菜单当前是否可见（单击悬浮球的收起判断用） */
    fun isActionPanelVisible(): Boolean =
        containerView?.panel?.isMenuVisible == true && radialParams != null

    /**
     * 单击关闭工具栏时的缩放反馈：悬浮球 0.95 → 1 轻微缩放（主线程），
     * 让用户明确感知「点击已响应」。纯渲染属性，不影响布局与位置。
     */
    fun pulseBubbleFeedback() {
        runOnMain {
            val bubble = containerView?.bubble ?: return@runOnMain
            bubble
                .animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(90)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction {
                    bubble
                        .animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(140)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }.start()
        }
    }

    /** 关闭除操作面板外的其它独立窗口面板（面板互斥） */
    private fun hideOtherPanels() {
        hideHistory()
        hideCapturePreview()
        hideResultInternal()
    }

    // ---------------------------------------------------------------------
    // 悬浮球拖动 / 贴边
    // ---------------------------------------------------------------------

    private fun buildBubbleDragListener(): FloatingDragListener {
        val dragListener =
            FloatingDragListener(
                onTap = {
                    // 点击时如果气泡处于半隐藏状态，先恢复完整显示，再走业务链路
                    restoreFromSnapIfNeeded()
                    listener?.onBubbleTap()
                },
                onLongPress = { toggleActionPanel() },
                onDragEnd = { _, _ ->
                    dragging = false
                    // 松手：仅当面板未展开时才做贴边吸附。
                    // 面板展开时容器宽度是「正方形（2×半径+按钮）」，用容器宽度算吸附
                    // 会把整个窗口拽到屏幕外 —— 四个卫星按钮只剩一个，其余全部出屏。
                    if (settings.snapToEdge && !isActionPanelVisible()) snapBubbleToEdge()
                    persistBubblePosition()
                },
                // 关键：阈值按 dp 换算（8dp ≈ 24px@3x）。默认的 12px 在 3x 屏上只有 4dp，
                // 手指「按住」的天然抖动就会超过它 → 长按被误判成拖动 → 四个按钮出不来。
                moveThresholdPx = FloatingDragListener.distanceThresholdDp(context, 8),
            )
        dragListener.onDragStart = { touchX, touchY ->
            dragging = true
            bubbleTouchOffsetX = touchX
            bubbleTouchOffsetY = touchY
        }
        // 面板展开时禁止把整个展开容器当球拖动：长按后手指的残余移动
        // 会把 436px 的窗口拽走（并把贴边吸附也带偏），四个按钮只剩一个在屏内。
        dragListener.onDrag = { rawX, rawY ->
            if (!isActionPanelVisible()) moveBubble(rawX, rawY)
        }
        return dragListener
    }

    /** 按拖动落点移动悬浮球（rawX/rawY 已由触点偏移校正），收敛在屏幕可用区 */
    private fun moveBubble(
        rawX: Int,
        rawY: Int,
    ) {
        val container = containerView ?: return
        val params = bubbleParams ?: return
        val screen = screenSize()
        val insets = windowInsets()
        val bubblePx = dpToPx(OverlayBubbleView.BUBBLE_SIZE_DP.toFloat())
        // 窗口可以半出屏，但悬浮球本体必须始终有一半可够到
        params.x =
            (rawX - bubbleTouchOffsetX).coerceIn(
                -bubblePx / 2,
                screen.x - bubblePx / 2,
            )
        params.y =
            (rawY - bubbleTouchOffsetY).coerceIn(
                insets.statusBarTop,
                (screen.y - insets.navBarBottom - bubblePx).coerceAtLeast(insets.statusBarTop),
            )
        updateViewSafely(container, params, "移动悬浮球")
    }

    /** 松手后按设置把气泡吸附到最近的边缘：悬浮球半隐藏（球心留在屏边内 1/4 球宽） */
    private fun snapBubbleToEdge() {
        val container = containerView ?: return
        val params = bubbleParams ?: return
        val screen = screenSize()
        val bubblePx = dpToPx(OverlayBubbleView.BUBBLE_SIZE_DP.toFloat())
        val bubbleCenterX = params.x + bubblePx / 2
        val threshold = dpToPx(EDGE_THRESHOLD_DP.toFloat())
        val target =
            if (bubbleCenterX < threshold) {
                -(bubblePx / 4)
            } else if (screen.x - bubbleCenterX < threshold) {
                screen.x - (bubblePx * 3 / 4)
            } else {
                // 不在边缘判定区内：不吸附
                return
            }
        snapped = true
        snapOffsetPx = target
        animateBubbleX(container, params, target, SNAP_ANIMATION_MS)
    }

    /** 把贴边半隐藏的气泡拉回完整显示（点击气泡 / 关闭贴边开关时调用） */
    private fun restoreFromSnapIfNeeded() {
        if (!snapped) return
        val container = containerView ?: return
        val params = bubbleParams ?: return
        val screen = screenSize()
        val bubblePx = dpToPx(OverlayBubbleView.BUBBLE_SIZE_DP.toFloat())
        val target = if (snapOffsetPx < 0) 0 else screen.x - bubblePx
        snapped = false
        snapOffsetPx = 0
        animateBubbleX(container, params, target, SNAP_ANIMATION_MS)
    }

    /** 气泡 x 轴的平移动画（贴边/恢复共用），每帧经 runOnMain 更新窗口 */
    private fun animateBubbleX(
        container: View,
        params: WindowManager.LayoutParams,
        targetX: Int,
        durationMs: Long,
    ) {
        val startX = params.x
        if (startX == targetX) return
        android.animation.ValueAnimator
            .ofInt(startX, targetX)
            .apply {
                duration = durationMs
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { anim ->
                    params.x = anim.animatedValue as Int
                    updateViewSafely(container, params, "贴边动画")
                }
            }.start()
    }

    /** 持久化悬浮球位置（异步落盘） */
    private fun persistBubblePosition() {
        val params = bubbleParams ?: return
        ServiceLocator.appScope.launch {
            ServiceLocator.settingsRepository.setFloatingPosition(params.x, params.y)
        }
    }

    /** 悬浮球窗口参数：56dp 固定尺寸，沿用上次位置，否则默认贴右缘、垂直约 1/3 处 */
    private fun buildBubbleParams(): WindowManager.LayoutParams {
        val size = bubbleSizePx()
        val screen = screenSize()
        val margin = dpToPx(DEFAULT_MARGIN_DP.toFloat())
        val x = settings.floatingX.takeIf { it >= 0 } ?: (screen.x - size - margin)
        val y = settings.floatingY.takeIf { it >= 0 } ?: (screen.y / 3)
        return WindowManager
            .LayoutParams(
                size,
                size,
                overlayType(),
                BUBBLE_FLAGS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
            }
    }

    // ---------------------------------------------------------------------
    // 面板统一管理
    // ---------------------------------------------------------------------

    /** 关闭所有面板（保留悬浮球；径向菜单是独立窗口，一并关闭） */
    fun hideAllPanels() {
        hideActionPanel()
        hideHistory()
        hidePresetCreate()
        hideCapturePreview()
        hideResultInternal()
    }

    // ---------------------------------------------------------------------
    // 面板：结果窗口
    // ---------------------------------------------------------------------

    /** 结果窗口的移动把手监听器（固定/取消固定时解绑与重绑用） */
    private var resultMoveListener: View.OnTouchListener? = null

    /**
     * 显示 / 更新结果窗口。
     *
     * 调用方（[FloatingService]）只需要把最新的 [ResultUiState] 丢进来，
     * 窗口已存在时原地重渲染（流式输出的每一段都走这里），不存在则创建。
     */
    fun showResult(state: ResultUiState) {
        runOnMain {
            val existing = resultView
            if (existing != null) {
                renderResult(existing, state)
                return@runOnMain
            }
            createResultWindow(state)
        }
    }

    private fun createResultWindow(state: ResultUiState) {
        hideAllPanels()
        val view = OverlayResultWindowView(context)
        view.listener =
            object : OverlayResultWindowView.Listener {
                override fun onClose() = listener?.onResultClose() ?: Unit

                override fun onRegenerate() = listener?.onResultRegenerate() ?: Unit

                override fun onFollowUp(prompt: String) = listener?.onResultFollowUp(prompt) ?: Unit
            }
        // 位置持久化：上次拖动过就沿用显式坐标，否则居中
        val params =
            if (settings.resultX >= 0 && settings.resultY >= 0) {
                buildResultParams().apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = settings.resultX
                    y = settings.resultY
                }
            } else {
                buildResultParams()
            }
        resultView = view
        resultParams = params
        applySizeLimits(view)
        addViewSafely(view, params, "结果窗口")

        // 尺寸持久化：上次手动调整过的宽高沿用，内容按新尺寸重新布局
        if (settings.resultW > 0 && settings.resultH > 0) {
            params.width = settings.resultW
            params.height = settings.resultH
            updateViewSafely(view, params, "应用上次结果窗口尺寸")
            view.applyWindowSize(settings.resultW, settings.resultH)
        }

        // 手势底座：移动/缩放各一套单一状态机
        val gestureBinder =
            WindowGestureBinder(
                windowManager = windowManager,
                maxRightPx = { screenSize().x },
                maxBottomPx = { screenSize().y - windowInsets().navBarBottom },
                clamp = { p -> clampWindowToUsableArea(view, p) },
            )

        // 右下角缩放把手：按住拖动调整大小（与右上角移动把手严格区分）
        view.resizeGrip().setOnTouchListener(
            gestureBinder.resizeHandle(
                window = view,
                handle = view.resizeGrip(),
                paramsProvider = { resultParams },
                onSizeChanged = { w, h -> view.applyWindowSize(w, h) },
                onPersistSize = { w, h ->
                    ServiceLocator.appScope.launch {
                        ServiceLocator.settingsRepository.setResultSize(w, h)
                    }
                },
                minWidthPx = dpToPx(MIN_RESIZE_DP.toFloat()),
                minHeightPx = dpToPx(MIN_RESIZE_DP.toFloat()),
            ),
        )

        // 越界吸附（仅显式坐标需要；居中重力不会越界）
        view.post {
            if (params.gravity != Gravity.CENTER && clampWindowToUsableArea(view, params)) {
                updateViewSafely(view, params, "吸附结果窗口到屏幕内")
            }
        }

        // 固定状态持久化：上次固定过则本次保持
        val pinnedInit = settings.resultPinned
        view.setPinned(pinnedInit)

        // 点阵把手拖拽：固定状态时解绑，取消固定重绑
        resultMoveListener =
            gestureBinder.moveHandle(
                window = view,
                handle = view.dragHandle(),
                paramsProvider = { resultParams },
                onPersistPosition = { x, y ->
                    ServiceLocator.appScope.launch {
                        ServiceLocator.settingsRepository.setResultPosition(x, y)
                    }
                },
            )
        view.dragHandle().setOnTouchListener(if (pinnedInit) null else resultMoveListener)

        // 固定/取消固定：锁定/解锁拖拽 + 固定时忽略点击外部关闭（View 内判断）+ 持久化
        view.pinListener = { pinned ->
            view.setPinned(pinned)
            view.dragHandle().setOnTouchListener(if (pinned) null else resultMoveListener)
            if (pinned) {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                params.gravity = Gravity.TOP or Gravity.START
                params.x = loc[0]
                params.y = loc[1]
                updateViewSafely(view, params, "固定结果窗口")
            }
            Toast
                .makeText(
                    context,
                    context.getString(
                        if (pinned) R.string.overlay_preview_pin_toast_pinned else R.string.overlay_preview_pin_toast_unpinned,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            ServiceLocator.appScope.launch {
                ServiceLocator.settingsRepository.setResultPinned(pinned)
                ServiceLocator.settingsRepository.setResultPosition(params.x, params.y)
            }
        }

        // 追问 IME：获得焦点时动态移除 FLAG_NOT_FOCUSABLE + showSoftInput（见 bindFollowUpIme）
        bindFollowUpIme(view, params)
        // 必须在 addView 之后再渲染：View 只有 attach 到窗口才会真正 measure/layout，
        // 提前渲染会让「滚动到底部」的 post 落在一个零高度的 ScrollView 上，滚动失效。
        renderResult(view, state)
    }

    /**
     * 绑定追问输入框与输入法/窗口焦点的联动。
     *
     * 追问输入框在悬浮窗里（默认 FLAG_NOT_FOCUSABLE），点它不会自动弹 IME。
     * 修复逻辑：获得焦点时动态移除 FLAG_NOT_FOCUSABLE（窗口可获焦）→ 下一帧
     * requestFocus + showSoftInput 弹起输入法；失焦时收起键盘并加回 flag，
     * 把焦点还给底层应用，不闪烁、不跳位。
     */
    private fun bindFollowUpIme(
        view: OverlayResultWindowView,
        params: WindowManager.LayoutParams,
    ) {
        view.onFollowUpFocusChanged = { hasFocus ->
            setWindowFocusable(view, params, hasFocus)
            if (hasFocus) {
                // updateViewLayout 之后窗口才变为可获焦，推迟一帧再弹键盘
                view.post { view.showFollowUpKeyboard() }
            } else {
                view.hideFollowUpKeyboard()
            }
        }
    }

    /** 把状态快照渲染到结果窗口 */
    private fun renderResult(
        view: OverlayResultWindowView,
        state: ResultUiState,
    ) {
        view.reset(state.title, state.statusTip)
        if (state.content.isNotEmpty()) {
            view.appendChunk(state.content)
        }
        view.setStreaming(state.isStreaming)
        view.setError(state.errorText)
        view.setStatusTip(state.statusTip)
        // 流式过程中禁止追问：一轮输出完成后才允许追问
        view.canFollowUp = state.canFollowUp && !state.isStreaming
    }

    /** 关闭结果窗口并回收 */
    fun hideResult() {
        resultView?.let { view ->
            // 先解绑点阵把手与缩放把手的监听：View 会被回收，留着监听器会拿到失效的窗口参数
            view.dragHandle().setOnTouchListener(null)
            view.resizeGrip().setOnTouchListener(null)
            view.clearListener()
            removeViewSafely(view, "结果窗口")
        }
        resultView = null
        resultParams = null
        resultMoveListener = null
    }

    /** 移除结果窗口并清理其拖拽监听（避免窗口已移除却仍持有回调） */
    private fun hideResultInternal() {
        resultView?.let { view ->
            view.dragHandle().setOnTouchListener(null)
            view.resizeGrip().setOnTouchListener(null)
            view.clearListener()
            removeViewSafely(view, "结果窗口")
        }
        resultView = null
        resultParams = null
        resultMoveListener = null
    }

    // ---------------------------------------------------------------------
    // 窗口参数构建（全部 WRAP_CONTENT：高度随内容自适应）
    // ---------------------------------------------------------------------

    private fun buildCenteredPanelParams(): WindowManager.LayoutParams =
        WindowManager
            .LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                PANEL_FLAGS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
            }

    /** 截图预览窗口的显式坐标参数（拖动 / 固定后的位置，x/y 由调用方写入） */
    private fun buildPreviewPositionedParams(): WindowManager.LayoutParams =
        WindowManager
            .LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                PANEL_FLAGS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

    /**
     * 结果窗口参数。
     *
     * 初始带 FLAG_NOT_FOCUSABLE：结果窗口大部分时间只是「读」，不该抢走底层应用的焦点，
     * 否则用户正在输入的聊天框会突然失去光标。需要输入时由 [setWindowFocusable] 动态移除。
     */
    private fun buildResultParams(): WindowManager.LayoutParams =
        WindowManager
            .LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                RESULT_FLAGS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
                // 让 IME 弹出时窗口整体上移，而不是被键盘盖住输入框
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }

    /** 系统栏 insets（状态栏 / 导航栏），读取失败时全为 0 兜底 */
    private data class ScreenInsets(
        val statusBarTop: Int,
        val navBarBottom: Int,
    )

    private fun windowInsets(): ScreenInsets {
        val insets =
            runCatching {
                val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
                wm.currentWindowMetrics.windowInsets.getInsets(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars(),
                )
            }.getOrElse { android.graphics.Insets.NONE }
        return ScreenInsets(statusBarTop = insets.top, navBarBottom = insets.bottom)
    }

    // ---------------------------------------------------------------------
    // 面板：截屏预览
    // ---------------------------------------------------------------------

    /**
     * 显示截图预览面板。
     *
     * 之所以把「面板创建」和「位图加载」分开（见 [updatePreviewBitmap]）：
     * 截屏本身是异步的，先让用户看到面板与加载提示，体感上不会有「点了没反应」的空白期。
     *
     * 位置与固定：
     * - 有保存过的位置 → 用显式坐标（TOP|START + x/y），否则居中；
     * - 固定状态与位置都从 DataStore 读取（AppSettings.previewPinned/X/Y），
     *   下次打开沿用上次的固定/位置；
     * - 拖拽挂在标题栏（预览框 [OverlayCapturePreviewView.titleBar]），
     *   固定时通过 [OverlayCapturePreviewView.setDragEnabled](false) 屏蔽手势。
     */
    fun showCapturePreview() {
        hideAllPanels()
        previewHiding = false
        val view = OverlayCapturePreviewView(context)
        view.listener =
            object : OverlayCapturePreviewView.Listener {
                override fun onConfirm() = listener?.onCaptureConfirm() ?: Unit

                override fun onRetry() = listener?.onCaptureRetry() ?: Unit

                override fun onCancel() = hideCapturePreview()
            }
        view.showTip(context.getString(R.string.overlay_capture_processing))

        // 尺寸定稿再上屏：addView 之前先按最终内容 measure 一次，
        // 窗口第一帧就是最终宽高，杜绝「先小后大」的尺寸跳变；
        // 弹出动画只用 alpha/scale/translation（transform-only），动画期间零重布局。
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )

        // 位置持久化：有历史位置就用显式坐标，否则居中；越界由 post 吸附兜底
        val savedX = settings.previewX
        val savedY = settings.previewY
        val params =
            if (savedX >= 0 && savedY >= 0) {
                buildPreviewPositionedParams().apply {
                    x = savedX
                    y = savedY
                }
            } else {
                buildCenteredPanelParams()
            }
        previewView = view
        previewParams = params
        addViewSafely(view, params, "截图预览")

        // 尺寸持久化：上次手动调整过的宽高沿用，内容按新尺寸重新布局
        if (settings.previewW > 0 && settings.previewH > 0) {
            params.width = settings.previewW
            params.height = settings.previewH
            updateViewSafely(view, params, "应用上次截图预览尺寸")
            view.applyWindowSize(settings.previewW, settings.previewH)
        }

        // 初始位置越界自动吸附进屏幕可用区（仅显式坐标需要；居中重力不会越界）
        // 同时限制窗口高度 ≤ 屏幕可用高度 70%：内容超限时内部滚动，绝不留大片空白
        view.post {
            clampPreviewIntoBounds(view, params)
            val insets = windowInsets()
            val screen = screenSize()
            val usable = (screen.y - insets.statusBarTop - insets.navBarBottom).coerceAtLeast(1)
            val maxH = (usable * PANEL_MAX_SCREEN_RATIO).toInt()
            view.setContentMaxHeight(maxH)
            if (view.height > maxH) {
                params.height = maxH
                updateViewSafely(view, params, "限制截图预览高度")
                view.applyWindowSize((params.width.takeIf { it > 0 } ?: view.width), maxH)
            }
        }

        // 弹出动画：220ms FastOutSlowIn 组合动画（alpha/scale/translationY）
        view.playEnterAnimation()

        // 手势底座：每个窗口一份 WindowGestureBinder，移动/缩放各只有一套局部状态机
        val gestureBinder =
            WindowGestureBinder(
                windowManager = windowManager,
                maxRightPx = { screenSize().x },
                maxBottomPx = { screenSize().y - windowInsets().navBarBottom },
                clamp = { p -> clampWindowToUsableArea(view, p) },
            )

        // 右下角缩放把手：按住拖动调整大小（与右上角移动把手严格区分）
        view.resizeGrip().setOnTouchListener(
            gestureBinder.resizeHandle(
                window = view,
                handle = view.resizeGrip(),
                paramsProvider = { previewParams },
                onSizeChanged = { w, h -> view.applyWindowSize(w, h) },
                onPersistSize = { w, h ->
                    ServiceLocator.appScope.launch {
                        ServiceLocator.settingsRepository.setPreviewSize(w, h)
                    }
                },
                minWidthPx = dpToPx(MIN_RESIZE_DP.toFloat()),
                minHeightPx = dpToPx(MIN_RESIZE_DP.toFloat()),
            ),
        )

        // 点阵移动把手：固定状态时解绑（setDragEnabled(false)），取消固定重绑
        previewMoveListener =
            gestureBinder.moveHandle(
                window = view,
                handle = view.dragHandle(),
                paramsProvider = { previewParams },
                onPersistPosition = { x, y -> persistPreviewPosition(x, y) },
            )
        view.setDragEnabled(!settings.previewPinned, previewMoveListener)
        view.setPinned(settings.previewPinned)

        // 固定/取消固定切换
        view.pinListener = { pinned ->
            view.setPinned(pinned)
            view.setDragEnabled(!pinned, previewMoveListener)
            if (pinned) {
                // 固定：把当前实际屏幕位置固化为显式坐标，无任何跳变
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                params.gravity = Gravity.TOP or Gravity.START
                params.x = loc[0]
                params.y = loc[1]
                updateViewSafely(view, params, "固定截图预览窗口")
            }
            Toast
                .makeText(
                    context,
                    context.getString(
                        if (pinned) R.string.overlay_preview_pin_toast_pinned else R.string.overlay_preview_pin_toast_unpinned,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            persistPreviewPinned(pinned)
            persistPreviewPosition(params.x, params.y)
        }
    }

    /** 预览窗口的移动把手监听器（固定/取消固定时解绑与重绑用） */
    private var previewMoveListener: View.OnTouchListener? = null

    /**
     * 把窗口坐标收敛进屏幕可用区（状态栏/导航栏之间）。
     * 预览 / 结果 / 历史面板的移动与吸附共用这一套钳制逻辑 —— 坐标计算只保留一份。
     *
     * @return 是否真的发生了修正（调用方据此决定要不要 updateViewLayout）
     */
    private fun clampWindowToUsableArea(
        view: View,
        params: WindowManager.LayoutParams,
    ): Boolean {
        val screen = screenSize()
        val insets = windowInsets()
        val clampedX = params.x.coerceIn(0, (screen.x - view.width).coerceAtLeast(0))
        val clampedY =
            params.y.coerceIn(
                insets.statusBarTop,
                (screen.y - insets.navBarBottom - view.height).coerceAtLeast(insets.statusBarTop),
            )
        if (clampedX == params.x && clampedY == params.y) return false
        params.x = clampedX
        params.y = clampedY
        return true
    }

    /** 初始位置越界时吸附进屏幕可用区；居中重力直接跳过 */
    private fun clampPreviewIntoBounds(
        view: OverlayCapturePreviewView,
        params: WindowManager.LayoutParams,
    ) {
        if (params.gravity == Gravity.CENTER) return
        if (clampWindowToUsableArea(view, params)) {
            updateViewSafely(view, params, "吸附截图预览窗口到屏幕内")
        }
    }

    /** 持久化固定状态（异步落盘，不阻塞主线程） */
    private fun persistPreviewPinned(pinned: Boolean) {
        ServiceLocator.appScope.launch {
            ServiceLocator.settingsRepository.setPreviewPinned(pinned)
        }
    }

    /** 持久化预览窗口最后位置（异步落盘，不阻塞主线程） */
    private fun persistPreviewPosition(
        x: Int,
        y: Int,
    ) {
        ServiceLocator.appScope.launch {
            ServiceLocator.settingsRepository.setPreviewPosition(x, y)
        }
    }

    /** 预览面板已存在时更新其中的位图 */
    fun updatePreviewBitmap(bitmap: android.graphics.Bitmap) {
        runOnMain { previewView?.showBitmap(bitmap) }
    }

    /** 在预览面板上展示提示（截屏失败等） */
    fun updatePreviewTip(text: CharSequence?) {
        runOnMain { previewView?.showTip(text) }
    }

    /** 关闭预览面板：先播 160ms 反向收起动画，动画结束后再移除窗口（不瞬间消失） */
    fun hideCapturePreview() {
        val view = previewView
        if (view == null) {
            previewParams = null
            previewMoveListener = null
            return
        }
        // 收起动画单飞：正在收起的窗口不再重复触发
        if (previewHiding) return
        previewHiding = true
        view.setDragEnabled(false, null)
        view.resizeGrip().setOnTouchListener(null)
        view.pinListener = null
        view.playExitAnimation {
            runOnMain {
                removeViewSafely(view, "截图预览")
                previewHiding = false
            }
        }
        // 立即清空引用：收起动画期间用户可以马上再次截图弹出新预览，互不干扰
        previewView = null
        previewParams = null
        previewMoveListener = null
    }

    // ---------------------------------------------------------------------
    // 面板：历史记录 / 预设选择
    // ---------------------------------------------------------------------

    /**
     * 显示历史记录面板。
     *
     * @param items 已排序（时间倒序）的记录，内部只取前若干条
     */
    fun showHistory(items: List<HistoryItem>) {
        runOnMain {
            hideAllPanels()
            val view = createHistoryView()
            view.listener =
                object : OverlayHistoryPanelView.Listener {
                    override fun onCopy(item: HistoryItem) = listener?.onHistoryCopy(item) ?: Unit

                    override fun onDelete(item: HistoryItem) = listener?.onHistoryDelete(item) ?: Unit

                    override fun onReAsk(item: HistoryItem) = listener?.onHistoryReAsk(item) ?: Unit

                    override fun onClose() = hideHistory()
                }
            view.showHistory(items)
            attachHistoryView(view)
        }
    }

    /** 关闭历史面板（解绑点阵把手的拖拽监听） */
    fun hideHistory() {
        historyView?.let { view ->
            view.dragHandle().setOnTouchListener(null)
            removeViewSafely(view, "历史面板")
        }
        historyView = null
        historyParams = null
        historyMoveListener = null
    }

    /** 历史面板的移动把手监听器 */
    private var historyMoveListener: View.OnTouchListener? = null

    /** 按屏幕可用空间给历史面板设置尺寸上限、位置与固定/拖拽能力并加入窗口 */
    private fun attachHistoryView(view: OverlayHistoryPanelView) {
        val params =
            if (settings.historyX >= 0 && settings.historyY >= 0) {
                buildPreviewPositionedParams().apply {
                    x = settings.historyX
                    y = settings.historyY
                }
            } else {
                buildCenteredPanelParams()
            }
        // 列表区高度上限 = 屏幕可用高度的 70%：内容短时自适应，内容长时内部滚动
        val screen = screenSize()
        val insets = windowInsets()
        val usable = (screen.y - insets.statusBarTop - insets.navBarBottom).coerceAtLeast(1)
        view.setMaxListHeight((usable * HISTORY_PANEL_MAX_RATIO).toInt())
        historyView = view
        historyParams = params
        addViewSafely(view, params, "历史面板")

        // 越界吸附（仅显式坐标需要）
        view.post {
            if (params.gravity != Gravity.CENTER && clampWindowToUsableArea(view, params)) {
                updateViewSafely(view, params, "吸附历史面板到屏幕内")
            }
        }

        // 点阵把手拖拽：WindowGestureBinder 单一状态机（固定按钮已删除，无固定状态）
        val gestureBinder =
            WindowGestureBinder(
                windowManager = windowManager,
                maxRightPx = { screenSize().x },
                maxBottomPx = { screenSize().y - windowInsets().navBarBottom },
                clamp = { p -> clampWindowToUsableArea(view, p) },
            )
        historyMoveListener =
            gestureBinder.moveHandle(
                window = view,
                handle = view.dragHandle(),
                paramsProvider = { historyParams },
                onPersistPosition = { x, y ->
                    ServiceLocator.appScope.launch {
                        ServiceLocator.settingsRepository.setHistoryPosition(x, y)
                    }
                },
            )
        view.dragHandle().setOnTouchListener(historyMoveListener)
    }

    /**
     * 显示预设指令选择面板。
     *
     * 复用历史面板的容器：二者都是「标题 + 可滚动条目列表 + 关闭」，
     * 差别只在条目内容与点击行为，单独再写一个 View 只会让两处样式各自漂移。
     *
     * @param allowCreate true 时列表底部显示「+ 新建指令」二级入口
     */
    fun chooseFromPresets(
        options: List<PresetOption>,
        allowCreate: Boolean = false,
        onOpenManager: (() -> Unit)? = null,
        onChosen: (PresetOption) -> Unit,
    ) {
        hideAllPanels()
        val view = createHistoryView()
        view.listener =
            object : OverlayHistoryPanelView.Listener {
                override fun onCopy(item: HistoryItem) = Unit

                override fun onDelete(item: HistoryItem) = Unit

                override fun onReAsk(item: HistoryItem) = Unit

                override fun onClose() = hideHistory()
            }
        // 空列表时的「打开预设管理页」入口（3.1.0 起无内置指令兜底）
        view.onOpenPresetManager = onOpenManager
        // 选择回调先关闭面板再交还业务层，避免业务层弹新窗口时旧面板还盖在上面
        view.showPresets(options.map { it.toPreset() }) { chosen ->
            hideHistory()
            onChosen(PresetOption.from(chosen))
        }
        // 「+ 新建指令」二级入口：弹出新建指令弹窗（不跳 Activity、不用剪贴板）
        view.onCreatePreset =
            if (allowCreate) {
                {
                    hideHistory()
                    showPresetCreateDialog()
                }
            } else {
                null
            }
        attachHistoryView(view)
    }

    // ---------------------------------------------------------------------
    // 面板：新建指令弹窗
    // ---------------------------------------------------------------------

    /** 新建指令弹窗（当前实例） */
    private var presetCreateView: OverlayPresetCreateView? = null

    /** 新建指令弹窗参数 */
    private var presetCreateParams: WindowManager.LayoutParams? = null

    /**
     * 弹出「新建指令」二级弹窗（继承统一悬浮窗底座）。
     *
     * - WRAP_CONTENT：高度随字段自适应；
     * - 点阵把手可拖（WindowGestureBinder）；
     * - 无 × 按钮：点外部空白 / 取消关闭；
     * - 窗口刻意不带 FLAG_NOT_FOCUSABLE：两个输入框要能弹出 IME。
     */
    fun showPresetCreateDialog() {
        runOnMain {
                hidePresetCreate()
            val view = OverlayPresetCreateView(context)
            view.listener =
                object : OverlayPresetCreateView.Listener {
                    override fun onSave(
                        name: String,
                        prompt: String,
                    ) {
                        val saved = presetCreateView
                        presetCreateListener?.onSaved(name, prompt)
                        if (saved != null) {
                            hidePresetCreate()
                        }
                    }

                    override fun onCancel() {
                        presetCreateListener?.onCancelled()
                        hidePresetCreate()
                    }
                }
            presetCreateView = view
            val params = buildPresetCreateParams()
            presetCreateParams = params
            addViewSafely(view, params, "新建指令弹窗")

            // 点阵把手拖拽：单一状态机（弹窗不持久化位置，重开居中）
            val gestureBinder =
                WindowGestureBinder(
                    windowManager = windowManager,
                    maxRightPx = { screenSize().x },
                    maxBottomPx = { screenSize().y - windowInsets().navBarBottom },
                    clamp = { p -> clampWindowToUsableArea(view, p) },
                )
            view.dragHandle().setOnTouchListener(
                gestureBinder.moveHandle(
                    window = view,
                    handle = view.dragHandle(),
                    paramsProvider = { presetCreateParams },
                    onPersistPosition = { _, _ -> },
                ),
            )
        }
    }

    /** 保存/取消回调（由 FloatingService 注册，弹窗关闭后自动置空） */
    var presetCreateListener: PresetCreateListener? = null

    interface PresetCreateListener {
        fun onSaved(
            name: String,
            prompt: String,
        )

        fun onCancelled()
    }

    /** 关闭新建指令弹窗 */
    fun hidePresetCreate() {
        presetCreateView?.let { view ->
            view.dragHandle().setOnTouchListener(null)
            view.listener = null
            removeViewSafely(view, "新建指令弹窗")
        }
        presetCreateView = null
        presetCreateParams = null
        presetCreateListener = null
    }

    private fun buildPresetCreateParams(): WindowManager.LayoutParams =
        WindowManager
            .LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                PANEL_FLAGS and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }

    /** 创建历史面板实例（尚未加入 WindowManager） */
    private fun createHistoryView(): OverlayHistoryPanelView = OverlayHistoryPanelView(context)


    /** 当前窗口类型：统一使用 TYPE_APPLICATION_OVERLAY（API 26+ 唯一可用的普通悬浮窗类型） */
    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /**
     * 动态切换窗口是否可获焦。
     *
     * 必须成对调用：需要输入时移除 FLAG_NOT_FOCUSABLE 并 `updateViewLayout`，
     * 否则 IME 无法弹起；输入结束后重新加回该 flag，把焦点还给底层应用，
     * 不然悬浮窗会长期「吸」着焦点，用户在别的应用里点输入框会莫名其妙打不出字。
     */
    fun setWindowFocusable(
        view: View?,
        params: WindowManager.LayoutParams?,
        focusable: Boolean,
    ) {
        if (view == null || params == null) return
        val changed =
            if (focusable) {
                val had = (params.flags and FLAG_NOT_FOCUSABLE) != 0
                params.flags = params.flags and FLAG_NOT_FOCUSABLE.inv()
                had
            } else {
                val had = (params.flags and FLAG_NOT_FOCUSABLE) == 0
                params.flags = params.flags or FLAG_NOT_FOCUSABLE
                had
            }
        if (changed) {
            updateViewSafely(view, params, "切换窗口可获焦($focusable)")
        }
    }

    // ---------------------------------------------------------------------
    // 尺寸与定位辅助
    // ---------------------------------------------------------------------

    /**
     * 给结果窗口设置尺寸上限。
     *
     * 不设上限的话，一条长回复会把窗口撑到整屏，遮住用户正在看的界面，
     * 也失去了「悬浮小窗」的意义。宽度上限同时保证正文有稳定的换行位置。
     *
     * 注意：这里**绝不能**改动 root 的 layoutParams。root 的 layoutParams 就是
     * WindowManager 持有的那份参数（addView 时被搭上去的），把它替换成
     * FrameLayout.LayoutParams 会让后续 updateViewLayout 直接抛
     * `IllegalArgumentException: WindowManager.LayoutParams required`。
     * 因此尺寸约束全部下沉到 root 内部的子 View（ScrollView 的 maxHeight 等）。
     */
    private fun applySizeLimits(view: OverlayResultWindowView) {
        val screen = screenSize()
        val maxWidth =
            resources()
                .getDimensionPixelSize(R.dimen.overlay_result_max_width)
                .coerceAtMost((screen.x * MAX_WIDTH_SCREEN_RATIO).roundToInt())
        val maxHeight =
            resources()
                .getDimensionPixelSize(R.dimen.overlay_result_max_height)
                .coerceAtMost((screen.y * MAX_HEIGHT_SCREEN_RATIO).roundToInt())
        // 正文区：高度自适应但不超过上限，超出后由 ScrollView 自己滚动
        view.resultScrollView().maxHeight = maxHeight
        // 正文宽度上限：约束内容 TextView（maxWidth 收缩布局，不留横向空白；
        // 不再用 minimumWidth 硬撑窗口宽度——那正是「内容少但窗口留白」的元凶之一）
        view.setContentMaxWidth(maxWidth)
    }

    // ---------------------------------------------------------------------
    // WindowManager 安全封装
    //
    // 悬浮窗的增删改在服务被系统回收、显示器切换等场景下会抛异常；
    // 这些异常如果逃逸到主线程会直接让前台服务崩溃，因此统一在这里兜底记录。
    // ---------------------------------------------------------------------

    /**
     * 主线程守卫：WindowManager 的 add/update/removeView 以及 View 的
     * setLayoutParams/requestLayout 都只能在主线程执行。
     *
     * 已经在主线程时同步执行（避免 post 带来的时序错乱）；
     * 否则丢到主线程队列。全局只用一个 Handler，避免到处 new 造成泄漏。
     */
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    /**
     * 悬浮窗硬性保证：任何窗口都绝不允许携带 FLAG_NOT_TOUCHABLE。
     * 该 flag 会让整个窗口收不到触摸事件（拖拽把手、按钮全部失灵），
     * 一旦发现立即清除并记录日志 —— 这是「拖拽失效」问题的最后一道防线。
     */
    private fun stripNotTouchable(params: WindowManager.LayoutParams): Boolean {
        val had = (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
        if (had) {
            Log.w(TAG, "检测到 FLAG_NOT_TOUCHABLE，已自动清除（防止拖拽/点击失效）")
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        return had
    }

    private fun addViewSafely(
        view: View,
        params: WindowManager.LayoutParams,
        tag: String,
    ) {
        runOnMain {
            try {
                stripNotTouchable(params)
                // 幂等：重复呼出时先移除可能残留的同一实例，避免重复 addView 崩溃
                runCatching { windowManager.removeView(view) }
                windowManager.addView(view, params)
            } catch (e: Exception) {
                Log.e(TAG, "添加窗口失败：$tag", e)
            }
        }
    }

    private fun updateViewSafely(
        view: View,
        params: WindowManager.LayoutParams,
        tag: String,
    ) {
        runOnMain {
            try {
                stripNotTouchable(params)
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
                Log.e(TAG, "更新窗口失败：$tag", e)
            }
        }
    }

    private fun removeViewSafely(
        view: View,
        tag: String,
    ) {
        runOnMain {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "移除窗口失败：$tag", e)
            }
        }
    }

    private fun setVisibilitySafely(
        view: View?,
        visibility: Int,
        params: WindowManager.LayoutParams?,
        tag: String,
    ) {
        if (view == null) return
        runOnMain {
            view.visibility = visibility
            if (params != null) {
                updateViewSafely(view, params, tag)
            }
        }
    }

    private fun screenSize(): Point {
        val size = Point()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            size.x = bounds.width()
            size.y = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
        }
        return size
    }

    private fun resources() = context.resources

    private fun dpToPx(dp: Float): Int = (dp * context.resources.displayMetrics.density).roundToInt()

    /** 悬浮球固定尺寸（px）：取自 OverlayBubbleView 的唯一常量定义 */
    private fun bubbleSizePx(): Int = dpToPx(OverlayBubbleView.BUBBLE_SIZE_DP.toFloat())

    companion object {
        private const val TAG = "OverlayManager"

        /**
         * 悬浮球容器窗口 flags。
         *
         * - FLAG_NOT_FOCUSABLE：悬浮球绝不能抢焦点，否则底层应用会失去输入光标；
         * - FLAG_LAYOUT_NO_LIMITS：允许窗口贴到屏幕边缘之外（贴边半隐藏需要把窗口推到屏幕外）；
         * - FLAG_LAYOUT_IN_SCREEN：以整个屏幕（含状态栏区域）为坐标系，
         *   否则 y=0 会从状态栏下方开始计算，与用户拖动时看到的坐标对不上；
         * - FLAG_WATCH_OUTSIDE_TOUCH：把窗口外的点击以 ACTION_OUTSIDE 投递给容器，
         *   实现「点击空白处收起面板」（见 OverlayBubbleContainerView.onTouchEvent）。
         */
        private const val BUBBLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        /**
         * 面板 flags：不能获焦（避免抢走底层应用的输入光标），
         * 但**不能**加 FLAG_LAYOUT_NO_LIMITS，否则会盖住状态栏与导航栏。
         * 带 FLAG_HARDWARE_ACCELERATED：预览窗口的弹出动画与位图绘制走 GPU，
         * 丝滑不卡顿；PixelFormat.TRANSLUCENT 由各构建函数统一设置，
         * 弹出时无黑边/白底闪烁。
         */
        private const val PANEL_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        /**
         * 结果窗口 flags：与面板一致，可获焦状态由 setWindowFocusable 动态切换。
         * 带 FLAG_WATCH_OUTSIDE_TOUCH：点击卡片外部空白会以 ACTION_OUTSIDE 投递给
         * OverlayResultWindowView，实现「点外部关闭」。
         */
        private const val RESULT_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        /** 触发贴边的边缘距离（dp） */
        private const val EDGE_THRESHOLD_DP = 24

        /** 贴边动画时长：太快像瞬移，太慢会拖住用户的下一次操作 */
        private const val SNAP_ANIMATION_MS = 180L

        /** 首次显示时悬浮球距屏幕边缘的默认留白（dp） */
        private const val DEFAULT_MARGIN_DP = 8

        /** 历史面板列表区高度占屏幕可用高度的比例上限 */
        private const val HISTORY_PANEL_MAX_RATIO = 0.7f

        /** 面板（预览/结果等）内容高度占屏幕可用高度的比例上限（超出内部滚动） */
        private const val PANEL_MAX_SCREEN_RATIO = 0.7f

        /** 悬浮窗口最小可调尺寸（dp）：防止缩到无法操作 */
        private const val MIN_RESIZE_DP = 200

        /** 结果窗口宽度上限不超过屏幕宽度的该比例，避免小屏上贴边 */
        private const val MAX_WIDTH_SCREEN_RATIO = 0.9f

        /** 结果窗口高度上限不超过屏幕高度的该比例 */
        private const val MAX_HEIGHT_SCREEN_RATIO = 0.7f
    }
}
