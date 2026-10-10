package com.screenshot.ai.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screenshot.ai.BuildConfig
import com.screenshot.ai.R
import com.screenshot.ai.ui.common.InfoChip
import com.screenshot.ai.ui.common.SectionLabel
import com.screenshot.ai.ui.common.SettingRow
import com.screenshot.ai.ui.theme.PageHorizontalPadding
import com.screenshot.ai.util.PermissionUtils

/**
 * 首页。
 *
 * 布局自上而下：
 *  1. 顶部标题区（App 名称 + 副标题 + 右上角设置入口）
 *  2. 功能开关区（悬浮工具栏 Switch）
 *  3. 入口区（提问历史 / 预设指令管理 / 关于）
 *  4. 底部版本号
 *
 * 权限相关的开关（无障碍、通知、开机自启、电池优化）刻意收进设置页，
 * 首页保持干净。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenHistory: () -> Unit,
    onOpenPreset: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val serviceKilled by viewModel.serviceKilledEvent.collectAsStateWithLifecycle()

    // 服务被系统回收：提示用户开关已复位
    LaunchedEffect(serviceKilled) {
        if (serviceKilled) {
            snackbarHostState.showSnackbar(context.getString(R.string.main_service_killed))
            viewModel.consumeServiceKilledEvent()
        }
    }

    // 无障碍服务未开启提醒：截图功能完全依赖它，但入口收在设置页里，
    // 因此在首页给一次明确提示并直接跳转，避免用户点悬浮球后才发现截不了屏。
    LaunchedEffect(state.accessibilityGranted) {
        if (!state.accessibilityGranted) {
            val result =
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.main_need_accessibility),
                    actionLabel = context.getString(R.string.main_settings),
                )
            if (result == SnackbarResult.ActionPerformed) {
                PermissionUtils.openAccessibilitySettings(context)
            }
        }
    }

    // 入场动画：各区块依次淡入上浮，形成轻微 stagger
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.main_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.main_settings),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState()),
        ) {
            // ---------------- 功能开关区 ----------------
            SectionLabel(text = stringResource(R.string.main_section_switch))

            StaggerItem(visible = appeared, index = 0) {
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PageHorizontalPadding),
                    shape = MaterialTheme.shapes.large,
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    SettingRow(
                        title = stringResource(R.string.main_floating_title),
                        leading = {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailing = {
                            Switch(
                                checked = state.serviceRunning,
                                onCheckedChange = { checked ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val accepted = viewModel.setFloatingEnabled(checked)
                                    if (!accepted) {
                                        // 缺少悬浮窗权限：引导用户去授权，开关保持原状（自动回弹）
                                        PermissionUtils.openOverlaySettings(context)
                                    }
                                },
                            )
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    Text(
                        text = stringResource(R.string.main_floating_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    )

                    // 当前模型 + 默认指令，让用户一眼看清快捷提问会走哪条链路
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InfoChip(text = state.modelLabel.ifBlank { stringResource(R.string.common_unknown) })
                        if (state.defaultPresetName.isNotBlank()) {
                            InfoChip(
                                text = state.defaultPresetName,
                                container = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }

            // ---------------- 入口区 ----------------
            SectionLabel(text = stringResource(R.string.main_section_entry))

            StaggerItem(visible = appeared, index = 1) {
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PageHorizontalPadding),
                    shape = MaterialTheme.shapes.large,
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    EntryRow(
                        icon = Icons.Rounded.History,
                        title = stringResource(R.string.main_entry_history),
                        onClick = onOpenHistory,
                    )
                    EntryDivider()
                    EntryRow(
                        icon = Icons.Rounded.Tune,
                        title = stringResource(R.string.main_entry_preset),
                        onClick = onOpenPreset,
                    )
                    EntryDivider()
                    EntryRow(
                        icon = Icons.Rounded.Info,
                        title = stringResource(R.string.main_entry_about),
                        onClick = onOpenAbout,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // ---------------- 底部版本号 ----------------
            StaggerItem(visible = appeared, index = 2) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.main_version_format, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/** 带序号的错落入场动画容器 */
@Composable
private fun StaggerItem(
    visible: Boolean,
    index: Int,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(tween(durationMillis = 260, delayMillis = index * 70)) +
                slideInVertically(tween(durationMillis = 260, delayMillis = index * 70)) { it / 10 },
    ) {
        content()
    }
}

/** 入口行：图标 + 标题 + 右箭头，整行可点击并带涟漪 */
@Composable
private fun EntryRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EntryDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 62.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}
