package com.screenshot.ai.overlay

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.screenshot.ai.R
import com.screenshot.ai.databinding.OverlayPresetCreateBinding
import com.screenshot.ai.util.Haptics

/**
 * 「新建指令」二级弹窗（悬浮窗内弹出，继承统一悬浮窗底座）。
 *
 * - 高度 WRAP_CONTENT：字段/按钮按内容自适应，无留白；
 * - 点阵把手可拖动（由 OverlayManager 用 WindowGestureBinder 绑定）；
 * - 没有右上角 ×：点弹窗外空白（ACTION_OUTSIDE，窗口带 FLAG_WATCH_OUTSIDE_TOUCH）
 *   或「取消」关闭；
 * - 无「从剪贴板粘贴」功能；
 * - 保存前校验：名称与内容均必填，缺失时红字提示（不静默失败）。
 */
class OverlayPresetCreateView(context: android.content.Context) : FrameLayout(context) {

    /** 保存/取消回调 */
    interface Listener {
        fun onSave(
            name: String,
            prompt: String,
        )

        fun onCancel()
    }

    var listener: Listener? = null

    private val binding: OverlayPresetCreateBinding =
        OverlayPresetCreateBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        isClickable = true
        val primary = resolveThemeColor(android.R.attr.textColorPrimary)
        val secondary = resolveThemeColor(android.R.attr.textColorSecondary)
        binding.presetCreateTitle.setTextColor(primary)
        binding.presetCreateError.setTextColor(resolveThemeColor(android.R.attr.colorError))
        binding.presetCreateCancel.setTextColor(primary)
        binding.presetCreateSave.setOnClickListener { view ->
            Haptics.tap(view)
            submit()
        }
        binding.presetCreateCancel.setOnClickListener { view ->
            Haptics.tap(view)
            listener?.onCancel()
        }
    }

    /** 点阵拖拽把手，供 OverlayManager 挂 WindowGestureBinder */
    fun dragHandle(): View = binding.presetCreateDragHandle

    /** 点击弹窗外空白关闭（配合窗口 FLAG_WATCH_OUTSIDE_TOUCH） */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            listener?.onCancel()
            return true
        }
        return super.onTouchEvent(event)
    }

    /** 保存：校验必填项，通过后回调（业务层负责写入并刷新列表） */
    private fun submit() {
        val name = binding.presetCreateName.text?.toString()?.trim().orEmpty()
        val prompt = binding.presetCreatePrompt.text?.toString()?.trim().orEmpty()
        if (name.isEmpty() || prompt.isEmpty()) {
            binding.presetCreateError.visibility = View.VISIBLE
            return
        }
        binding.presetCreateError.visibility = View.GONE
        listener?.onSave(name, prompt)
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typed = context.theme.obtainStyledAttributes(intArrayOf(attr))
        return try {
            typed.getColor(0, 0)
        } finally {
            typed.recycle()
        }
    }
}
