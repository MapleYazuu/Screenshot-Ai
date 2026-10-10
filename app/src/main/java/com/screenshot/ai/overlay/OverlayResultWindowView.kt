package com.screenshot.ai.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.screenshot.ai.R
import com.screenshot.ai.databinding.OverlayResultWindowBinding
import com.screenshot.ai.util.Haptics
import com.screenshot.ai.util.MarkdownRenderer
import com.screenshot.ai.util.dp

/**
 * AI 回复窗口：流式正文 + 追问输入 + 复制 / 重新生成 / 关闭。
 *
 * 关键设计：
 * - 正文用 [StringBuilder] 累积增量，再整体 `text =` 写回。相比 `append()`，
 *   整体赋值能保证 TextView 的 `textIsSelectable` 在长文本下不会因为跨度重建而丢失选区；
 * - 流式过程中「自动贴底」只在用户本来就在底部时生效，用户往上翻看前文时不会被强行拽回底部；
 * - 追问输入与正文共用一个窗口：如果拆成两个 WindowManager 窗口，
 *   IME 焦点会在两个 window token 之间来回切换，弹出体验很差。
 */
class OverlayResultWindowView(
    context: Context,
) : FrameLayout(context) {
    /**
     * 结果窗口交互回调。
     * 复制在窗口内部完成（纯 UI 行为），其余交给 [com.screenshot.ai.service.OverlayManager]，
     * 因为取消请求、重建会话等都需要访问仓储与网络层。
     */
    interface Listener {
        /** 用户关闭窗口：调用方需要同时取消正在进行的流式请求 */
        fun onClose()

        /** 重新生成上一条回复 */
        fun onRegenerate()

        /** 追问：会复用同一个 ChatSession，从而保留首轮截图上下文 */
        fun onFollowUp(prompt: String)
    }

    var listener: Listener? = null

    /**
     * 是否允许追问。
     * 一轮输出完成后才允许追问：流式过程中发新请求会污染上下文，
     * 且同一条助手消息会被写入两次历史。
     */
    var canFollowUp: Boolean = false
        set(value) {
            field = value
            binding.resultFollowUpRow.visibility = if (value) View.VISIBLE else View.GONE
            updateSendEnabled()
        }

    private val binding: OverlayResultWindowBinding =
        OverlayResultWindowBinding.inflate(LayoutInflater.from(context), this, true)

    /** 流式正文累积缓冲 */
    private val buffer = StringBuilder()

    /** 是否正在流式输出（决定是否显示光标动画字符） */
    private var streaming = false

    init {
        isClickable = true
        // 正文颜色由 XML 固定为 overlay_result_text（浅色 #333 / 深色浅灰），
        // 与卡片背景形成「标题重、正文轻」的层次，这里不再用主题主色覆盖
        binding.resultTitle.setTextColor(resolveThemeColor(android.R.attr.textColorPrimary))
        // 错误/失败提示：次级灰色（用户要求），与正文字体层级区分
        binding.resultError.setTextColor(resolveThemeColor(android.R.attr.textColorSecondary))
        binding.resultStatusTip.setTextColor(resolveThemeColor(android.R.attr.textColorSecondary))
        val caption = resolveThemeColor(android.R.attr.textColorSecondary)
        binding.resultPin.setOnClickListener { view ->
            Haptics.tap(view)
            pinListener?.invoke(!pinned)
        }
        binding.resultCopy.setOnClickListener { view ->
            Haptics.tap(view)
            copyToClipboard()
        }
        // 关闭：不显眼的纯文字兜底按钮（次级色）；主关闭方式是点击卡片外部空白
        binding.resultClose.setTextColor(caption)
        binding.resultClose.setOnClickListener { view ->
            Haptics.tap(view)
            listener?.onClose()
        }
        binding.resultRegenerate.setOnClickListener { view ->
            Haptics.tap(view)
            listener?.onRegenerate()
        }
        binding.resultFollowUpSend.setOnClickListener { view ->
            Haptics.tap(view)
            sendFollowUp()
        }
        binding.resultFollowUpInput.setOnFocusChangeListener { _, hasFocus ->
            onFollowUpFocusChanged?.invoke(hasFocus)
        }
        // Markdown 链接可点击（http/https 跳转浏览器）
        MarkdownRenderer.enableLinkClicks(binding.resultContent)
    }

    /**
     * 点击卡片外部空白自动关闭。
     *
     * 前置条件：窗口 flags 带 FLAG_WATCH_OUTSIDE_TOUCH（见 OverlayManager.RESULT_FLAGS），
     * 系统会把窗口外的点击以 ACTION_OUTSIDE 投递到这里；此时调用 onClose。
     * **固定状态下忽略外部点击**：固定 = 不自动消失（见 [pinned]）。
     */
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
            if (!pinned) {
                listener?.onClose()
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    /** 图钉点击回调：参数为点击后的目标状态（true = 固定） */
    var pinListener: ((pinned: Boolean) -> Unit)? = null

    /** 当前是否已固定（固定时不响应点击外部关闭） */
    private var pinned: Boolean = false

    /** 固定/取消固定的视觉状态切换（实心图钉=已固定，空心图钉=未固定） */
    fun setPinned(pinned: Boolean) {
        this.pinned = pinned
        val tint =
            if (pinned) {
                resolveThemeColor(androidx.appcompat.R.attr.colorPrimary)
            } else {
                resolveThemeColor(android.R.attr.textColorSecondary)
            }
        binding.resultPin.apply {
            setCompoundDrawablesWithIntrinsicBounds(
                if (pinned) R.drawable.ic_pin else R.drawable.ic_pin_outline,
                0,
                0,
                0,
            )
            compoundDrawables[0]?.mutate()?.setTintList(android.content.res.ColorStateList.valueOf(tint))
            contentDescription =
                context.getString(
                    if (pinned) R.string.overlay_preview_unpin else R.string.overlay_preview_pin,
                )
        }
    }

    /** 「拖出为独立卡片」已移除（3.1.0 重构）：窗口直接通过点阵把手拖动 */

    /**
     * 追问输入框焦点变化回调。
     *
     * 悬浮窗默认带 `FLAG_NOT_FOCUSABLE`，在该 flag 生效期间 IME 不会弹起；
     * OverlayManager 需要据此动态移除 / 加回该 flag（见 setWindowFocusable 的说明）。
     */
    var onFollowUpFocusChanged: ((hasFocus: Boolean) -> Unit)? = null

    /**
     * 重置为一次新请求的初始状态。
     *
     * @param title 标题（指令名）
     * @param tip   附加提示（OCR 降级、默认指令等），null 表示不显示
     */
    fun reset(
        title: String,
        tip: String?,
    ) {
        buffer.setLength(0)
        streaming = false
        binding.resultTitle.text = title
        binding.resultContent.text = ""
        binding.resultError.visibility = View.GONE
        setStatusTip(tip)
        canFollowUp = false
        binding.resultFollowUpInput.setText("")
        // 恢复正文区与操作按钮的常规状态（错误态会临时隐藏/置灰它们）
        binding.resultScroll.visibility = View.VISIBLE
        setActionButtonsEnabled(true)
    }

    /** 追加一段流式增量 */
    fun appendChunk(chunk: String) {
        if (chunk.isEmpty()) return
        // 一旦有正文内容，立刻恢复正文区与操作按钮（消息态可能隐藏过它们）
        if (binding.resultScroll.visibility != View.VISIBLE) {
            binding.resultScroll.visibility = View.VISIBLE
            setActionButtonsEnabled(true)
        }
        // 记录追加前是否已经贴底：用户手动上翻后不再自动滚动
        val wasAtBottom = isScrolledToBottom()
        buffer.append(chunk)
        renderContent()
        if (wasAtBottom) scrollToBottom()
    }

    /** 设置是否处于流式输出中（用于显示光标与禁用追问） */
    fun setStreaming(value: Boolean) {
        streaming = value
        renderContent()
        scrollToBottom()
    }

    /** 显示错误信息；[message] 为空时隐藏错误行 */
    fun setError(message: String?) {
        if (message.isNullOrBlank()) {
            binding.resultError.visibility = View.GONE
        } else {
            binding.resultError.visibility = View.VISIBLE
            binding.resultError.text = message
        }
        // 「仅消息」模式（如截图失败）：没有正文时不显示空白正文区，
        // 并置灰复制/重新生成（只保留关闭），避免用户误点
        val messageOnly = !message.isNullOrBlank() && buffer.isEmpty()
        binding.resultScroll.visibility = if (messageOnly) View.GONE else View.VISIBLE
        setActionButtonsEnabled(!messageOnly)
    }

    /** 复制 / 重新生成 按钮启用状态（置灰 = 当前没有可操作的正文） */
    private fun setActionButtonsEnabled(enabled: Boolean) {
        binding.resultCopy.isEnabled = enabled
        binding.resultCopy.alpha = if (enabled) 1f else 0.35f
        binding.resultRegenerate.isEnabled = enabled
        binding.resultRegenerate.alpha = if (enabled) 1f else 0.35f
    }

    /** 设置状态提示（默认指令提示 / OCR 降级提示 / 等待追问提示） */
    fun setStatusTip(tip: String?) {
        if (tip.isNullOrBlank()) {
            binding.resultStatusTip.visibility = View.GONE
        } else {
            binding.resultStatusTip.visibility = View.VISIBLE
            binding.resultStatusTip.text = tip
        }
    }

    /** 当前正文（用于写入历史记录） */
    fun currentText(): String = buffer.toString()

    /** 关闭前置空 listener，避免窗口被移除后仍回调 */
    fun clearListener() {
        listener = null
    }

    private fun sendFollowUp() {
        if (!canFollowUp) return
        val prompt =
            binding.resultFollowUpInput.text
                ?.toString()
                ?.trim()
                .orEmpty()
        if (prompt.isEmpty()) return
        binding.resultFollowUpInput.setText("")
        listener?.onFollowUp(prompt)
    }

    private fun copyToClipboard() {
        val text = buffer.toString()
        if (text.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        // 复制反馈只提示，不弹 Toast：悬浮场景下 Toast 会飘到别的应用上方造成干扰
        setStatusTip(context.getString(R.string.overlay_copied))
    }

    /**
     * 把缓冲内容写回 TextView。
     *
     * **流式兼容**：streaming 期间只显示纯文本（Markdown 语法半截，渲染会闪烁/错位），
     * 并补一个光标字符；整段输出结束（streaming=false）才异步渲染完整 Markdown。
     */
    private fun renderContent() {
        if (streaming) {
            binding.resultContent.text = buffer.toString() + STREAMING_CARET
        } else {
            MarkdownRenderer.renderAsync(binding.resultContent, buffer.toString())
        }
    }

    private fun updateSendEnabled() {
        val enabled = canFollowUp
        binding.resultFollowUpSend.isEnabled = enabled
        binding.resultFollowUpSend.alpha = if (enabled) 1f else 0.4f
        binding.resultFollowUpInput.isEnabled = enabled
    }

    /** 是否已滚动到底部（留 8dp 容差，避免像素级误差导致判定失败） */
    private fun isScrolledToBottom(): Boolean {
        val child = binding.resultScroll.getChildAt(0) ?: return true
        val tolerance = (resources.displayMetrics.density * 8).toInt()
        return child.height - (binding.resultScroll.height + binding.resultScroll.scrollY) <= tolerance
    }

    /** 下一帧再滚动：此刻可能尚未完成 measure/layout，直接 fullScroll 会滚不到位 */
    private fun scrollToBottom() {
        binding.resultScroll.post {
            binding.resultScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    /** 读取当前主题颜色属性：面板背景本身随 day/night 变化，正文颜色必须跟着走 */
    private fun resolveThemeColor(attr: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) {
            return ContextCompat.getColor(context, android.R.color.black)
        }
        return if (value.resourceId != 0) {
            ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    /** 点阵拖拽把手（只有按住这里才能拖动窗口），供 OverlayManager 挂拖拽监听 */
    fun dragHandle(): View = binding.resultDragHandle

    /** 右下角缩放把手：只有按住这里才能调整窗口大小（与移动把手严格区分） */
    fun resizeGrip(): View = binding.resultResizeGrip

    /** 正文内容最大宽度（px）：TextView maxWidth 收缩布局，短内容不留横向空白 */
    fun setContentMaxWidth(maxWidthPx: Int) {
        binding.resultContent.maxWidth = maxWidthPx
    }

    /**
     * 窗口被手动缩放后按新尺寸重新布局：
     * 卡片撑满（扣除外圈阴影空间），正文滚动区随之加高，内容不被裁剪。
     */
    fun applyWindowSize(
        widthPx: Int,
        heightPx: Int,
    ) {
        val shadow = resources.getDimensionPixelSize(R.dimen.overlay_result_shadow_margin)
        binding.resultCard.layoutParams =
            FrameLayout.LayoutParams(
                (widthPx - shadow * 2).coerceAtLeast(1),
                (heightPx - shadow * 2).coerceAtLeast(1),
            ).apply {
                leftMargin = shadow
                topMargin = shadow
            }
        // 正文滚动上限随窗口加高（近似扣除标题/操作行/边距）
        binding.resultScroll.maxHeight = (heightPx - shadow * 2 - context.dp(200)).coerceAtLeast(context.dp(140))
        binding.resultCard.requestLayout()
    }


    /** 结果窗口根布局，供 OverlayManager 施加整体尺寸下限 */
    fun resultRootView(): View = binding.resultRoot

    /**
     * 正文滚动容器。
     *
     * 暴露它的原因是 OverlayManager 需要按屏幕高度设置 `maxHeight`；
     * 窗口高度是 wrap_content，直接改窗口尺寸会让短回复也占满一屏。
     */
    fun resultScrollView(): MaxHeightScrollView = binding.resultScroll

    /** 让错误行文字居中，视觉上更像一块提示条 */
    fun alignErrorCenter() {
        binding.resultError.gravity = Gravity.CENTER
    }

    /**
     * 弹出追问输入的软键盘。
     *
     * 必须在 OverlayManager 移除 FLAG_NOT_FOCUSABLE 之后再调用：
     * 窗口不可获焦时 showSoftInput 不会有任何效果。
     */
    fun showFollowUpKeyboard() {
        binding.resultFollowUpInput.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(binding.resultFollowUpInput, InputMethodManager.SHOW_IMPLICIT)
    }

    /** 收起软键盘：输入框失焦后必须主动收起，否则会残留一个不可见的输入连接 */
    fun hideFollowUpKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(binding.resultFollowUpInput.windowToken, 0)
    }

    companion object {
        /** 流式光标：用方块字符比 "..." 更不容易与正文混淆 */
        private const val STREAMING_CARET = "▌"

        /** 剪贴板标签，便于用户在剪贴板管理器里辨认来源 */
        private const val CLIP_LABEL = "screenshot-ai"
    }
}
