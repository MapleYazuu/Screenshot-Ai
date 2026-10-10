package com.screenshot.ai.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.screenshot.ai.data.prefs.AppSettings

// ---------------------------------------------------------------------------
// 配色（v2 · 高级感升级）
//
// 设计语言：去廉价感的关键是「低饱和中性底色 + 少量高质感强调色」。
// - 页面背景：高级浅灰 #F8F9FA（不再是与卡片无差别的灰白）；
// - 卡片：纯白 #FFFFFF，配合 2~4dp 柔和阴影产生层级；
// - 强调色：有质感的深紫罗兰 #5B4FCF（主）+ 低饱和辅助色，
//   按钮、开关、选中态统一由它驱动，不再五彩斑斓；
// - 深色模式：中性近黑 + 柔和紫，对比度充足但不刺眼。
// ---------------------------------------------------------------------------

private val Violet40 = Color(0xFF5B4FCF)
private val Violet80 = Color(0xFFB9AFFF)
private val VioletGrey40 = Color(0xFF645B77)
private val VioletGrey80 = Color(0xFFCCC2DC)
private val Moss40 = Color(0xFF3E6E5C)
private val Moss80 = Color(0xFF9FD1BD)

private val DarkColors =
    darkColorScheme(
        primary = Violet80,
        onPrimary = Color(0xFF2A2260),
        primaryContainer = Color(0xFF3F368F),
        onPrimaryContainer = Color(0xFFE6E0FF),
        secondary = VioletGrey80,
        onSecondary = Color(0xFF352D40),
        tertiary = Moss80,
        onTertiary = Color(0xFF0C382D),
        background = Color(0xFF121317),
        onBackground = Color(0xFFE5E5EA),
        surface = Color(0xFF1A1B21),
        onSurface = Color(0xFFE5E5EA),
        surfaceVariant = Color(0xFF26272E),
        onSurfaceVariant = Color(0xFFB8BAC4),
        surfaceContainer = Color(0xFF202129),
        surfaceContainerHigh = Color(0xFF27282F),
        outline = Color(0xFF6E707A),
        outlineVariant = Color(0xFF34353D),
    )

private val LightColors =
    lightColorScheme(
        primary = Violet40,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE8E4FF),
        onPrimaryContainer = Color(0xFF211A54),
        secondary = VioletGrey40,
        onSecondary = Color.White,
        tertiary = Moss40,
        onTertiary = Color.White,
        // 高级浅灰底色，与纯白卡片形成层级
        background = Color(0xFFF8F9FA),
        onBackground = Color(0xFF1B1C1E),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1B1C1E),
        surfaceVariant = Color(0xFFEFF1F5),
        onSurfaceVariant = Color(0xFF5A5D66),
        surfaceContainer = Color(0xFFF3F4F8),
        surfaceContainerHigh = Color(0xFFE9EBF0),
        outline = Color(0xFFD8DCE3),
        outlineVariant = Color(0xFFE4E7EC),
    )

/**
 * 全局主题。
 *
 * @param themeMode [AppSettings.THEME_SYSTEM] / [AppSettings.THEME_LIGHT] / [AppSettings.THEME_DARK]
 * @param dynamicColor Android 12+ 动态取色；默认关闭，保证品牌色在各机型一致
 */
@Composable
fun ScreenshotAiTheme(
    themeMode: String = AppSettings.THEME_SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themeMode) {
            AppSettings.THEME_DARK -> true
            AppSettings.THEME_LIGHT -> false
            else -> isSystemInDarkTheme()
        }

    val context = LocalContext.current
    val colorScheme =
        when {
            // 动态取色仅作为可选能力保留，默认关闭
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> DarkColors
            else -> LightColors
        }

    // 状态栏图标颜色跟随主题，避免深色模式下看不清
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
