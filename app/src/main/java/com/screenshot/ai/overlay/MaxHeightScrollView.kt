package com.screenshot.ai.overlay

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * 支持 `maxHeight` 的 ScrollView。
 *
 * 为什么需要它：
 * 结果窗口的窗口高度是 `wrap_content`（短回复不该占满一屏），但长回复必须有上限。
 * `ScrollView` 本身没有 `maxHeight` 属性，而直接改窗口尺寸会让短回复也撑到上限高度。
 * 在 `onMeasure` 里给高度加一个上限，就能让「内容短时自适应、内容长时内部滚动」
 * 同时成立。
 *
 * 使用约束：仅当父布局给的是 `wrap_content` 时才有效果；
 * 若父布局是 `match_parent`，上限不会生效（也不需要生效）。
 */
class MaxHeightScrollView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : ScrollView(context, attrs, defStyleAttr) {
        /** 高度上限（px）；<= 0 表示不限制 */
        var maxHeight: Int = 0

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            if (maxHeight > 0) {
                // 用一个「至多 maxHeight」的 spec 重新约束高度，再交给父类正常测量。
                // 不能简单地在测量后截断：那样内容会被裁掉而不是变成可滚动。
                val limited =
                    MeasureSpec.makeMeasureSpec(
                        maxHeight,
                        MeasureSpec.AT_MOST,
                    )
                super.onMeasure(widthMeasureSpec, limited)
                // 内容不足时收缩到实际高度，避免短回复下方留白
                if (measuredHeight > maxHeight) {
                    setMeasuredDimension(measuredWidth, maxHeight)
                }
                return
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
