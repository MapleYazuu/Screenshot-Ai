package com.screenshot.ai.overlay

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.screenshot.ai.R
import com.screenshot.ai.ServiceLocator
import com.screenshot.ai.data.model.HistoryItem
import com.screenshot.ai.data.model.Preset
import com.screenshot.ai.databinding.OverlayHistoryPanelBinding
import com.screenshot.ai.util.Haptics
import com.screenshot.ai.util.ImageUtils
import com.screenshot.ai.util.MarkdownRenderer
import com.screenshot.ai.util.dp
import kotlinx.coroutines.launch
import java.io.File

/**
 * 历史记录面板（同一套外观也用于「选择预设指令」，见 [showPresets]）。
 *
 * 为什么复用同一块 UI：
 * 两者都是「标题 + 一列可选条目 + 关闭」，且都只在悬浮窗里出现；
 * 复制一份布局只会让两处样式各自漂移。
 *
 * 为什么不用 RecyclerView：条数上限被业务限制在 30 条（见 [MAX_ROWS]），
 * 一次性 inflate 的开销远小于引入 Adapter/ViewHolder 带来的复杂度。
 */
class OverlayHistoryPanelView(
    context: Context,
) : FrameLayout(context) {
    /**
     * 历史记录交互回调。
     * 「重新提问」把原始条目整体回传，而不是只回传 id：
     * 悬浮窗没有 ViewModel 缓存，调用方直接用条目里的 imagePath / prompt 即可重跑链路。
     */
    interface Listener {
        /** 复制该条回复 */
        fun onCopy(item: HistoryItem)

        /** 删除该条记录 */
        fun onDelete(item: HistoryItem)

        /** 用原始截图与指令重新提问 */
        fun onReAsk(item: HistoryItem)

        /** 关闭面板 */
        fun onClose()
    }

    var listener: Listener? = null

    /**
     * 用户在预设选择模式下的选择回调。
     * 与 [listener] 分开：预设选择是「截图 → 提问」链路的中间步骤，
     * 由 FloatingService 注入一个一次性 lambda 更自然。
     */
    var onPresetChosen: ((Preset) -> Unit)? = null

    /** 「+ 新建指令」二级入口回调（非空时在预设列表底部显示该行） */
    var onCreatePreset: (() -> Unit)? = null

    /** 「打开预设管理页」回调（空列表提示中显示） */
    var onOpenPresetManager: (() -> Unit)? = null

    private val binding: OverlayHistoryPanelBinding =
        OverlayHistoryPanelBinding.inflate(LayoutInflater.from(context), this, true)

    private val primaryColor = resolveThemeColor(android.R.attr.textColorPrimary)
    private val secondaryColor = resolveThemeColor(android.R.attr.textColorSecondary)

    init {
        isClickable = true
        binding.historyTitle.setTextColor(primaryColor)
        binding.historyEmpty.setTextColor(secondaryColor)
        binding.historyClose.setTextColor(secondaryColor)
        binding.historyClose.setOnClickListener { view ->
            Haptics.tap(view)
            listener?.onClose()
        }
    }

    /** 标题栏（含点阵拖拽把手与关闭按钮） */
    fun titleBar(): View = binding.historyTitleBar

    /** 点阵拖拽把手：只有按住这里才能拖动窗口 */
    fun dragHandle(): View = binding.historyDragHandle

    /**
     * 展示历史记录。
     *
     * @param items 已按时间倒序排列的记录（仓库 SQL ORDER BY timestamp DESC）
     */
    fun showHistory(items: List<HistoryItem>) {
        binding.historyTitle.text = context.getString(R.string.history_title)
        renderRows(items.take(MAX_ROWS).map { item -> buildHistoryRow(item) })
    }

    /** 给列表区设置高度上限（px），内容超出时内部滚动 */
    fun setMaxListHeight(maxHeightPx: Int) {
        binding.historyScroll.maxHeight = maxHeightPx
    }

    /**
     * 展示预设指令列表，点击即回调 [onPresetChosen]。
     *
     * @param presets 可选指令
     * @param onChosen 选中回调，默认指令会带「默认」标记便于用户辨认
     */
    fun showPresets(
        presets: List<Preset>,
        onChosen: (Preset) -> Unit,
    ) {
        onPresetChosen = onChosen
        binding.historyTitle.text = context.getString(R.string.overlay_choose_preset)
        if (presets.isEmpty()) {
            // 空态（3.1.0 起无内置指令）：提示 + 新建 / 打开预设管理 两个入口
            renderRows(listOf(buildEmptyPresetState()))
            return
        }
        val rows = presets.map { preset -> buildPresetRow(preset) }.toMutableList()
        // 「+ 新建指令」二级入口（仅在调用方提供回调时显示）
        if (onCreatePreset != null) {
            rows.add(buildCreatePresetRow())
        }
        renderRows(rows)
    }

    /** 预设列表为空的提示卡片：提示文案 + [新建指令] + [预设管理] */
    private fun buildEmptyPresetState(): View {
        val card = newCard()
        card.addView(newLabel(context.getString(R.string.overlay_no_preset_choose), secondaryColor, false))
        val actions =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = newRowParams()
            }
        actions.addView(
            newActionButton(R.string.preset_add) { onCreatePreset?.invoke() },
        )
        actions.addView(
            newActionButton(R.string.overlay_open_preset_manager) { onOpenPresetManager?.invoke() },
        )
        card.addView(actions)
        return card
    }

    /** 「+ 新建指令」二级入口：Material 风格按钮（48dp 高、圆角、涟漪、与列表 16dp 间距） */
    private fun buildCreatePresetRow(): View {
        val wrapper =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = newRowParams()
            }
        val button =
            TextView(context).apply {
                text = "＋ " + context.getString(R.string.preset_add)
                gravity = android.view.Gravity.CENTER
                textSize = resources.getDimension(R.dimen.overlay_body_text_size) / resources.displayMetrics.scaledDensity
                setTextColor(primaryColor)
                background = context.getDrawable(R.drawable.bg_overlay_action_button)
                isClickable = true
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        context.dp(48),
                    ).apply { topMargin = context.dp(16) }
                setOnClickListener { view ->
                    Haptics.tap(view)
                    onCreatePreset?.invoke()
                }
            }
        wrapper.addView(button)
        return wrapper
    }


    /** 渲染列表；为空时显示空态文案 */
    private fun renderRows(rows: List<View>) {
        binding.historyContainer.removeAllViews()
        if (rows.isEmpty()) {
            binding.historyEmpty.visibility = View.VISIBLE
            return
        }
        binding.historyEmpty.visibility = View.GONE
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                row.layoutParams =
                    (row.layoutParams as LinearLayout.LayoutParams).apply {
                        topMargin = resources.getDimensionPixelSize(R.dimen.overlay_row_gap)
                    }
            }
            binding.historyContainer.addView(row)
        }
    }

    /**
     * 构建一条历史记录卡片。
     *
     * 收起态：时间 · 指令名（加粗）+ 回复摘要（最多两行）；
     * 点击整卡原地展开：完整回复 + 完整截图（IO 线程解码，主线程上屏）+ 操作行。
     */
    private fun buildHistoryRow(item: HistoryItem): View {
        val card = newCard()
        card.addView(newLabel(timeText(item.timestamp) + " · " + item.title, primaryColor, true))

        // 摘要：最多两行（要求「最多两行」）
        val reply = item.aiReply.ifBlank { item.prompt }
        card.addView(newLabel(reply, secondaryColor, false))

        // 展开区（初始隐藏）：截图置顶 → 完整回复（与 App 内历史页布局一致）
        val detail = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = newRowParams()
            visibility = View.GONE
        }

        val imageView =
            ImageView(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        resources.getDimensionPixelSize(R.dimen.overlay_result_min_height),
                    ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.overlay_row_gap) }
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                visibility = View.GONE
                setOnClickListener { imageView ->
                    Haptics.tap(imageView)
                    onScreenshotClicked?.invoke(item)
                }
            }
        detail.addView(imageView)

        val missingHint =
            newLabel(
                context.getString(R.string.history_image_missing),
                secondaryColor,
                false,
            ).apply { visibility = View.GONE }
        detail.addView(missingHint)

        val fullReply = newLabel(reply, primaryColor, false, maxLines = Int.MAX_VALUE)
        // 完整回复用 Markdown 渲染（与 AI 回复悬浮窗 / 提问历史页一致）
        MarkdownRenderer.enableLinkClicks(fullReply)
        MarkdownRenderer.renderAsync(fullReply, reply)
        detail.addView(fullReply)
        card.addView(detail)

        // 操作行：复制 / 删除 / 重新提问（点击均有轻振动反馈）
        val actions =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = newRowParams()
            }
        actions.addView(
            newActionButton(R.string.history_action_copy) { listener?.onCopy(item) },
        )
        actions.addView(
            newActionButton(R.string.history_action_delete) { listener?.onDelete(item) },
        )
        actions.addView(
            newActionButton(R.string.history_action_reask) { listener?.onReAsk(item) },
        )
        card.addView(actions)

        // 点击整卡：展开/收起 + 首次展开时异步加载截图（不阻塞主线程）
        card.isClickable = true
        card.setOnClickListener { view ->
            Haptics.tap(view)
            val expanding = detail.visibility != View.VISIBLE
            detail.visibility = if (expanding) View.VISIBLE else View.GONE
            if (expanding && !screenshotLoaded(imageView)) {
                loadScreenshot(item.imagePath, imageView, missingHint)
            }
        }
        card.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.alpha = 0.7f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.alpha = 1f
            }
            false
        }
        return card
    }

    /** 截图点击回调（全屏查看等，由 OverlayManager 注入；无回调时仅作占位） */
    var onScreenshotClicked: ((HistoryItem) -> Unit)? = null

    private fun screenshotLoaded(imageView: ImageView): Boolean = imageView.drawable != null

    /** IO 线程解码截图 → 主线程上屏；文件缺失时给出文字提示而不是空白 */
    private fun loadScreenshot(
        path: String,
        imageView: ImageView,
        missingHint: TextView,
    ) {
        if (path.isBlank() || !File(path).exists()) {
            runOnMain {
                imageView.visibility = View.GONE
                missingHint.visibility = View.VISIBLE
            }
            return
        }
        ServiceLocator.appScope.launch {
            val bitmap = ImageUtils.decodeSampled(path, maxSize = SCREENSHOT_MAX_EDGE)
            runOnMain {
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    imageView.visibility = View.VISIBLE
                    missingHint.visibility = View.GONE
                } else {
                    imageView.visibility = View.GONE
                    missingHint.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }

    /** 构建一条预设指令卡片（整行可点） */
    private fun buildPresetRow(preset: Preset): View {
        val card = newCard()
        val name =
            buildString {
                preset.icon?.takeIf { it.isNotBlank() }?.let { append(it).append(' ') }
                append(preset.name)
                if (preset.isDefault) append("（").append(context.getString(R.string.preset_default_badge)).append("）")
            }
        card.addView(newLabel(name, primaryColor, true))
        card.addView(newLabel(preset.prompt, secondaryColor, false))
        card.setOnClickListener { onPresetChosen?.invoke(preset) }
        // 整行可点时必须给出按压反馈，否则用户不知道点击是否生效
        card.isClickable = true
        card.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.alpha = 0.6f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.alpha = 1f
            }
            false
        }
        return card
    }

    private fun newCard(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = newRowParams()
            setPadding(
                resources.getDimensionPixelSize(R.dimen.overlay_row_padding),
                resources.getDimensionPixelSize(R.dimen.overlay_row_padding),
                resources.getDimensionPixelSize(R.dimen.overlay_row_padding),
                resources.getDimensionPixelSize(R.dimen.overlay_row_padding),
            )
            setBackgroundResource(R.drawable.bg_overlay_action_button)
        }

    private fun newRowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

    private fun newLabel(
        text: String,
        color: Int,
        bold: Boolean,
        maxLines: Int = if (bold) 1 else MAX_PREVIEW_LINES,
    ): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.overlay_body_text_size))
            this.maxLines = maxLines
            if (maxLines != Int.MAX_VALUE) ellipsize = TextUtils.TruncateAt.END
            if (bold) setTypeface(typeface, Typeface.BOLD)
            layoutParams = newRowParams()
        }

    private fun newActionButton(
        textRes: Int,
        onClick: () -> Unit,
    ): TextView =
        TextView(context).apply {
            text = context.getString(textRes)
            setTextColor(primaryColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.overlay_caption_text_size))
            gravity = Gravity.CENTER
            minHeight = resources.getDimensionPixelSize(R.dimen.overlay_button_height)
            setBackgroundResource(R.drawable.bg_overlay_action_button)
            val paddingH = resources.getDimensionPixelSize(R.dimen.overlay_button_padding_h)
            setPadding(paddingH, 0, paddingH, 0)
            layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = resources.getDimensionPixelSize(R.dimen.overlay_row_gap)
                }
            setOnClickListener { view ->
                Haptics.tap(view)
                onClick()
            }
        }

    /** 时间戳格式化：跟随系统 12/24 小时制，不硬编码格式串 */
    private fun timeText(timestamp: Long): String =
        DateFormat.getDateFormat(context).format(timestamp) + " " +
            DateFormat.getTimeFormat(context).format(timestamp)

    /** 读取主题颜色，保证浅色/深色模式下正文都可读 */
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

    companion object {
        /** 悬浮窗内最多展示的条数：再多会让面板高过屏幕，也无法快速定位 */
        private const val MAX_ROWS = 30

        /** 回复摘要行数上限：完整内容点击展开查看 */
        private const val MAX_PREVIEW_LINES = 2

        /** 展开区截图解码的最大边长（px）：悬浮面板只需要预览清晰度 */
        private const val SCREENSHOT_MAX_EDGE = 720
    }
}
