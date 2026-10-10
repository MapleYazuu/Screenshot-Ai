package com.screenshot.ai.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 形状规范（v2 · 高级感统一）：
 * 全局圆角收敛到 8 / 12 / 16 三档，卡片统一 16dp，
 * 不再使用 20dp+ 的超大圆角——大圆角 + 纯色卡片正是「廉价感」的主要来源。
 */
val AppShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )

/** 页面统一水平留白 */
val PageHorizontalPadding = 20.dp

/** 卡片之间统一间距 */
val CardSpacing = 14.dp

/** 区块之间统一间距 */
val SectionSpacing = 32.dp
