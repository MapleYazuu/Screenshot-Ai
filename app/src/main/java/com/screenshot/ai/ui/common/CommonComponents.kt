package com.screenshot.ai.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.screenshot.ai.ui.theme.PageHorizontalPadding
import com.screenshot.ai.ui.theme.SectionSpacing
import com.screenshot.ai.util.MarkdownRenderer

/**
 * 全局按钮按压反馈：按下时缩放 0.96 → 松开回弹 1.0，并触发一次轻振动
 * （HapticFeedbackType.TextHandleMove，等效 KEYBOARD_TAP 的轻触感）。
 *
 * 配合 Material3 按钮自带的 Ripple 使用：涟漪（视觉）+ 缩放（动势）+ 振动（触感），
 * 三通道反馈统一所有按钮的按压质感。
 *
 * @param interactionSource 按钮自身的 MutableInteractionSource（必须传给 Button 的
 *   interactionSource 参数，二者共享同一个实例，否则按压缩放不生效）
 */
@Composable
fun Modifier.pressScaleWithHaptic(interactionSource: MutableInteractionSource): Modifier {
    val haptic = LocalHapticFeedback.current
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(pressed) {
        if (pressed) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "pressScale",
    )
    return this.scale(scale)
}

/**
 * Markdown 富文本（Compose 侧）。
 *
 * Compose 侧不引入独立的 Markdown Compose 库，而是用 AndroidView 内嵌 TextView，
 * 复用 [MarkdownRenderer]（与悬浮窗同一套 Markwon 解析器与样式）——
 * 全应用 Markdown 方言、代码块样式、链接行为完全一致。
 *
 * @param markdown 完整 Markdown 文本（调用方保证已输出完成；流式半截内容请勿传入）
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            android.widget.TextView(context).apply {
                setTextIsSelectable(true)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(0xFF333333.toInt())
                MarkdownRenderer.enableLinkClicks(this)
                MarkdownRenderer.renderAsync(this, markdown)
            }
        },
        update = { view ->
            // markdown 变化时异步重渲染（解析在 IO、setText 回主线程）
            MarkdownRenderer.renderAsync(view, markdown)
        },
    )
}

/**
 * 区块标题：小字号 + 主题色 + 字间距，用于分隔页面内容分组。
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier =
            modifier.padding(
                start = PageHorizontalPadding,
                end = PageHorizontalPadding,
                top = SectionSpacing,
                bottom = 8.dp,
            ),
    )
}

/**
 * 小标签（Provider / 模型 / 默认 等），圆角胶囊样式。
 */
@Composable
fun InfoChip(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(container)
                .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/**
 * 空状态：图标 + 主标题 + 说明文案。
 * 使用 Emoji 作为插画，避免引入体积较大的图片资源。
 */
@Composable
fun EmptyState(
    emoji: String,
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = PageHorizontalPadding, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = emoji, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 带左图标、标题、副标题与右侧内容的通用行。
 * 首页入口、设置项都复用它，保证全站行高与留白一致。
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    // 标题右侧的内联小部件（例如「多模态」彩色小圆点），与标题同行但不占整行右侧
    titleTrailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                leading()
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 主标题：titleMedium 加粗，副标题：bodyMedium 低透明度 —— 层级分明的高级排版
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (titleTrailing != null) {
                    titleTrailing()
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}
