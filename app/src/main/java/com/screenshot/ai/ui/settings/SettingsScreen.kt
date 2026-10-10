package com.screenshot.ai.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screenshot.ai.R
import com.screenshot.ai.ai.AiProvider
import com.screenshot.ai.data.prefs.AppSettings
import com.screenshot.ai.service.FloatingService
import com.screenshot.ai.ui.common.SectionLabel
import com.screenshot.ai.ui.common.SettingRow
import com.screenshot.ai.ui.common.pressScaleWithHaptic
import com.screenshot.ai.ui.theme.PageHorizontalPadding
import com.screenshot.ai.util.AiErrorMessages
import com.screenshot.ai.util.LocaleManager
import com.screenshot.ai.util.PermissionUtils

/**
 * 设置界面。
 *
 * Provider 配置使用「展开式」而非二级页面：手机设置项天然适合就地展开，
 * 少一次跳转、也少一次状态同步。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val testState by viewModel.testState.collectAsStateWithLifecycle()
    val customConfig by viewModel.customConfig.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // 权限状态：每次组合重新查询（用户可能刚从系统设置返回）
    var permissionRefresh by remember { mutableStateOf(0) }
    val overlayGranted = remember(permissionRefresh) { viewModel.canDrawOverlays() }
    val accessibilityGranted = remember(permissionRefresh) { viewModel.isAccessibilityEnabled() }
    val notificationGranted = remember(permissionRefresh) { viewModel.isNotificationGranted() }
    val batteryGranted = remember(permissionRefresh) { viewModel.isIgnoringBatteryOptimizations() }
    val autoStart = settings.autoStartOnBoot

    // 展开中的 Provider 卡片数：>0 时临时隐藏悬浮球，避免它遮住输入框与按钮
    var expandedProviderCards by remember { mutableStateOf(0) }
    LaunchedEffect(expandedProviderCards) {
        FloatingService.setBubbleAvoidance(expandedProviderCards > 0)
    }
    DisposableEffect(Unit) {
        onDispose {
            // 离开设置页必须恢复悬浮球，否则球会永久消失
            FloatingService.setBubbleAvoidance(false)
        }
    }

    // 测试连接结果提示
    LaunchedEffect(testState) {
        when (val state = testState) {
            is SettingsViewModel.TestState.Success ->
                snackbarHostState.showSnackbar(context.getString(R.string.settings_test_ok))

            is SettingsViewModel.TestState.Failure ->
                snackbarHostState.showSnackbar(
                    context.getString(
                        R.string.settings_test_fail,
                        AiErrorMessages.localize(context, state.error),
                    ),
                )

            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState()),
        ) {
            // ==================== 模型管理 ====================
            SectionLabel(text = stringResource(R.string.settings_section_model))

            viewModel.providers.forEach { provider ->
                ProviderCard(
                    provider = provider,
                    selected = provider.id == settings.providerId,
                    selectedModelId = settings.modelId,
                    hasKey = viewModel.hasApiKey(provider.id),
                    apiKey = viewModel.apiKeyOf(provider.id),
                    baseUrl = viewModel.baseUrlOf(provider.id),
                    testing = (testState as? SettingsViewModel.TestState.Testing)?.providerId == provider.id,
                    onSelect = { viewModel.selectProvider(provider.id) },
                    onSaveKey = { key -> viewModel.saveApiKey(provider.id, key) },
                    onClearKey = { viewModel.clearApiKey(provider.id) },
                    onSaveBaseUrl = { url -> viewModel.saveBaseUrl(provider.id, url) },
                    onSelectModel = { modelId -> viewModel.selectModel(modelId) },
                    onTest = { apiKey, baseUrl, modelId ->
                        viewModel.testConnection(provider.id, apiKey, baseUrl, modelId)
                    },
                    onExpandedChanged = { open ->
                        expandedProviderCards += if (open) 1 else -1
                    },
                )
            }

            // 「其他 / 自定义模型」：OpenAI 兼容第三方接口，样式与内置卡片完全一致
            CustomModelCard(
                viewModel = viewModel,
                config = customConfig,
                onExpandedChanged = { open ->
                    expandedProviderCards += if (open) 1 else -1
                },
            )

            // （默认指令入口已按需求移除：统一到主页的「预设指令管理」中操作）

            // ==================== 悬浮球配置 ====================
            SectionLabel(text = stringResource(R.string.settings_section_floating))

            SettingsCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_floating_alpha),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Slider(
                        value = settings.floatingAlpha,
                        onValueChange = { viewModel.setFloatingAlpha(it) },
                        valueRange = 0.3f..1f,
                        steps = 6,
                    )
                }
                CardDivider()
                SettingRow(
                    title = stringResource(R.string.settings_floating_edge),
                    trailing = {
                        Switch(
                            checked = settings.snapToEdge,
                            onCheckedChange = { viewModel.setSnapToEdge(it) },
                        )
                    },
                )
                CardDivider()
                SettingRow(
                    title = stringResource(R.string.settings_low_quality),
                    trailing = {
                        Switch(
                            checked = settings.lowQuality,
                            onCheckedChange = { viewModel.setLowQuality(it) },
                        )
                    },
                )
            }

            // ==================== 权限管理 ====================
            SectionLabel(text = stringResource(R.string.settings_section_permission))

            SettingsCard {
                PermissionRow(
                    title = stringResource(R.string.settings_perm_accessibility),
                    subtitle = stringResource(R.string.settings_perm_accessibility_desc),
                    granted = accessibilityGranted,
                    onClick = {
                        PermissionUtils.openAccessibilitySettings(context)
                        permissionRefresh++
                    },
                )
                CardDivider()
                PermissionRow(
                    title = stringResource(R.string.settings_perm_overlay),
                    subtitle = stringResource(R.string.settings_perm_overlay_desc),
                    granted = overlayGranted,
                    onClick = {
                        PermissionUtils.openOverlaySettings(context)
                        permissionRefresh++
                    },
                )
                CardDivider()
                PermissionRow(
                    title = stringResource(R.string.settings_perm_notification),
                    subtitle = stringResource(R.string.settings_perm_notification_desc),
                    granted = notificationGranted,
                    onClick = {
                        PermissionUtils.openNotificationSettings(context)
                        permissionRefresh++
                    },
                )
                CardDivider()
                SettingRow(
                    title = stringResource(R.string.settings_perm_boot),
                    subtitle = stringResource(R.string.settings_perm_boot_desc),
                    trailing = {
                        Switch(checked = autoStart, onCheckedChange = { viewModel.setAutoStart(it) })
                    },
                )
                CardDivider()
                PermissionRow(
                    title = stringResource(R.string.settings_perm_battery),
                    subtitle = stringResource(R.string.settings_perm_battery_desc),
                    granted = batteryGranted,
                    onClick = {
                        PermissionUtils.requestIgnoreBatteryOptimizations(context)
                        permissionRefresh++
                    },
                )
            }

            // 定制 ROM 额外说明：只在国内机型展示，避免对原生 Android 用户造成干扰
            if (viewModel.vendorDisplayName() != null) {
                Spacer(Modifier.height(10.dp))
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PageHorizontalPadding),
                    shape = MaterialTheme.shapes.medium,
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                        ),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.settings_rom_detected, viewModel.vendorDisplayName().orEmpty()),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.settings_rom_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { PermissionUtils.openVendorAutoStartSettings(context) }) {
                            Text(stringResource(R.string.settings_rom_open))
                        }
                    }
                }
            }

            // ==================== 其他 ====================
            SectionLabel(text = stringResource(R.string.settings_section_other))

            SettingsCard {
                // 主题
                var themeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = themeExpanded,
                    onExpandedChange = { themeExpanded = it },
                ) {
                    SettingRow(
                        title = stringResource(R.string.settings_theme),
                        subtitle = themeLabel(settings.themeMode),
                        modifier =
                            Modifier
                                .menuAnchor()
                                .clickable { themeExpanded = true },
                        trailing = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    // 宽度与锚点一致、紧贴锚点下沿展开
                    SmoothDropdownMenu(
                        expanded = themeExpanded,
                        onDismissRequest = { themeExpanded = false },
                        modifier = Modifier.exposedDropdownSize(matchTextFieldWidth = true),
                    ) {
                        val systemLabel = stringResource(R.string.settings_theme_system)
                        val lightLabel = stringResource(R.string.settings_theme_light)
                        val darkLabel = stringResource(R.string.settings_theme_dark)
                        ThemeOption(systemLabel, AppSettings.THEME_SYSTEM, settings.themeMode) {
                            viewModel.setThemeMode(it)
                            themeExpanded = false
                        }
                        ThemeOption(lightLabel, AppSettings.THEME_LIGHT, settings.themeMode) {
                            viewModel.setThemeMode(it)
                            themeExpanded = false
                        }
                        ThemeOption(darkLabel, AppSettings.THEME_DARK, settings.themeMode) {
                            viewModel.setThemeMode(it)
                            themeExpanded = false
                        }
                    }
                }

                CardDivider()

                // 语言：同时决定界面语言与 AI 回复语言
                var languageExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = languageExpanded,
                    onExpandedChange = { languageExpanded = it },
                ) {
                    SettingRow(
                        title = stringResource(R.string.settings_language),
                        subtitle = languageLabel(settings.languageTag),
                        modifier =
                            Modifier
                                .menuAnchor()
                                .clickable { languageExpanded = true },
                        trailing = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    SmoothDropdownMenu(
                        expanded = languageExpanded,
                        onDismissRequest = { languageExpanded = false },
                        modifier = Modifier.exposedDropdownSize(matchTextFieldWidth = true),
                    ) {
                        // 「跟随系统」+ 5 种语言。语言名用各自的母语书写（Language 自名惯例），
                        // 因此这里刻意不翻译 settings_language_xx 这些字符串。
                        val options =
                            listOf(
                                stringResource(R.string.settings_language_system) to LocaleManager.TAG_SYSTEM,
                                stringResource(R.string.settings_language_zh) to LocaleManager.TAG_ZH,
                                stringResource(R.string.settings_language_en) to LocaleManager.TAG_EN,
                                stringResource(R.string.settings_language_ja) to LocaleManager.TAG_JA,
                                stringResource(R.string.settings_language_ko) to LocaleManager.TAG_KO,
                                stringResource(R.string.settings_language_ru) to LocaleManager.TAG_RU,
                            )
                        options.forEach { (label, tag) ->
                            LanguageOption(label, tag, settings.languageTag) {
                                viewModel.setLanguage(it)
                                languageExpanded = false
                            }
                        }
                    }
                }

                // 提示：语言同时影响 AI 回复语言
                Text(
                    text = stringResource(R.string.settings_language_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )

                CardDivider()

                SettingRow(
                    title = stringResource(R.string.settings_about),
                    modifier = Modifier.clickable(onClick = onOpenAbout),
                    leading = {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/** Provider 配置卡片：折叠显示状态，展开后可填 Key、选模型、测试连接 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCard(
    provider: AiProvider,
    selected: Boolean,
    selectedModelId: String,
    hasKey: Boolean,
    apiKey: String,
    baseUrl: String,
    testing: Boolean,
    onSelect: () -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onSaveBaseUrl: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    // 测试连接直接使用输入框里的值（API Key / Base URL / 模型 ID），不依赖已保存数据
    onTest: (apiKey: String, baseUrl: String, modelId: String) -> Unit,
    onExpandedChanged: (Boolean) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    var keyInput by remember(apiKey) { mutableStateOf(apiKey) }
    var baseUrlInput by remember(baseUrl) { mutableStateOf(baseUrl) }
    var keyVisible by remember { mutableStateOf(false) }
    // 模型选择的本地即时状态：点击圆圈/名称立刻高亮选中，点「保存」才持久化
    var selectedModelIdLocal by remember(selectedModelId) { mutableStateOf(selectedModelId) }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PageHorizontalPadding, vertical = 6.dp)
                // 核心动画：高度变化只由 animateContentSize 驱动，
                // 300ms + FastOutSlowInEasing —— 展开/收起平滑，不再生硬弹跳
                .animateContentSize(tween(durationMillis = 300, easing = FastOutSlowInEasing)),
        shape = MaterialTheme.shapes.large,
        // 展开与收起保持一致：纯白表面 + 2dp 柔和阴影，不再因展开变色
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        SettingRow(
            title = provider.displayName,
            subtitle =
                if (hasKey) {
                    stringResource(R.string.settings_api_key_saved)
                } else {
                    stringResource(R.string.settings_api_key_hint)
                },
            modifier =
                Modifier.clickable {
                    expanded = !expanded
                    onExpandedChanged(expanded)
                },
            leading = {
                // 选中标记
                Box(
                    modifier =
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            },
            // 去掉一直占着右侧的「支持图片输入」标签，
            // 改用标题旁的小圆点代表「多模态」，右侧只保留展开箭头
            titleTrailing = {
                if (provider.supportsVision) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.tertiary),
                    )
                }
            },
            trailing = {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        // 高度交给外层 animateContentSize；这里只负责内容渐显（延迟 80ms），
        // 不再使用默认的 expandVertically —— 避免「高度 + 内容」双重动画的生硬感
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = 80)),
            exit = fadeOut(animationSpec = tween(durationMillis = 140)),
        ) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
                Spacer(Modifier.height(4.dp))

                // 浅灰内嵌小卡片：把输入区域与纯白外层区分开（深色模式用 surfaceContainer）
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(inputAreaColor())
                            .padding(16.dp),
                ) {
                    Column {
                        // API Key 输入（密码态，可切换可见）
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.settings_api_key)) },
                            placeholder = { Text(stringResource(R.string.settings_api_key_hint)) },
                            singleLine = true,
                            visualTransformation =
                                if (keyVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                            trailingIcon = {
                                IconButton(onClick = { keyVisible = !keyVisible }) {
                                    Icon(
                                        imageVector =
                                            if (keyVisible) {
                                                Icons.Rounded.VisibilityOff
                                            } else {
                                                Icons.Rounded.Visibility
                                            },
                                        contentDescription = null,
                                    )
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                        )

                        Spacer(Modifier.height(12.dp))

                        // 自定义接口地址（留空即用默认）
                        OutlinedTextField(
                            value = baseUrlInput,
                            onValueChange = { baseUrlInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.settings_base_url)) },
                            placeholder = { Text(provider.defaultBaseUrl) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                        )

                        Spacer(Modifier.height(12.dp))

                        // 「设为默认」单选列表：
                        // 点击圆圈或模型名称**立即**在 UI 上选中（实心圆 + 淡紫高亮），
                        // 点「保存」才把选择持久化为全局默认模型；点击不触发卡片展开/收起。
                        Text(
                            text =
                                stringResource(R.string.settings_default_model) + "：" +
                                    (
                                        provider.findModel(selectedModelIdLocal)?.displayName
                                            ?: provider.defaultModel?.displayName
                                            ?: stringResource(R.string.common_unknown)
                                    ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        provider.models.forEach { model ->
                            val isSelected = model.id == selectedModelIdLocal
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isSelected) {
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                                            } else {
                                                Color.Transparent
                                            },
                                        )
                                        .clickable { selectedModelIdLocal = model.id }
                                        .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedModelIdLocal = model.id },
                                )
                                Text(
                                    text = model.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (model.supportsVision) {
                                    Spacer(Modifier.width(8.dp))
                                    // 彩色小圆点标记多模态
                                    Box(
                                        modifier =
                                            Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.tertiary),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // 按钮行：保存 = 实心主色，测试连接 = 描边，权重 1:1，间距 12dp
                        // 按钮行：保存 = 实心主色，测试连接 = 描边，权重 1:1，间距 12dp
                        // 按压反馈：Ripple + 0.96 缩放 + 轻振动
                        val saveInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        val testInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = {
                                    // 保存：此时才把「选中模型 + Key + 接口地址」持久化为全局配置
                                    onSaveKey(keyInput)
                                    onSaveBaseUrl(baseUrlInput)
                                    onSelectModel(selectedModelIdLocal)
                                    if (!selected) onSelect()
                                },
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .pressScaleWithHaptic(saveInteraction),
                                interactionSource = saveInteraction,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(stringResource(R.string.common_save))
                            }

                            OutlinedButton(
                                // 测试连接直接使用输入框里的值（未保存也能测）；仅在请求进行中禁用防连点
                                onClick = { onTest(keyInput, baseUrlInput, selectedModelIdLocal) },
                                enabled = !testing,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .pressScaleWithHaptic(testInteraction),
                                interactionSource = testInteraction,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                if (testing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    stringResource(
                                        if (testing) {
                                            R.string.settings_testing
                                        } else {
                                            R.string.settings_test_connection
                                        },
                                    ),
                                )
                            }
                        }

                        if (hasKey) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = {
                                    keyInput = ""
                                    onClearKey()
                                }) {
                                    Text(
                                        text = stringResource(R.string.settings_clear_key),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 视觉能力说明：只在展开时显示
                Text(
                    text =
                        if (provider.supportsVision) {
                            stringResource(R.string.settings_vision_supported)
                        } else {
                            stringResource(R.string.settings_vision_unsupported)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
            }
        }
    }
}

/** 输入区域内嵌卡片的底色：浅色模式 #F5F5F5，深色模式用主题 surfaceContainer */
@Composable
private fun inputAreaColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
        Color(0xFFF5F5F5)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

/** 权限行：右侧显示已开启 / 未开启，点击跳转系统设置 */
@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.clickable(onClick = onClick),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text =
                        stringResource(
                            if (granted) R.string.settings_perm_granted else R.string.settings_perm_denied,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (granted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** 统一卡片容器 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
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
        content = { Column { content() } },
    )
}

@Composable
private fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun ThemeOption(
    label: String,
    value: String,
    current: String,
    onSelect: (String) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            if (value == current) {
                Icon(Icons.Rounded.Check, contentDescription = null)
            }
        },
        onClick = { onSelect(value) },
    )
}

@Composable
private fun LanguageOption(
    label: String,
    value: String,
    current: String,
    onSelect: (String) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            if (value == current) {
                Icon(Icons.Rounded.Check, contentDescription = null)
            }
        },
        onClick = { onSelect(value) },
    )
}

@Composable
private fun themeLabel(mode: String): String =
    when (mode) {
        AppSettings.THEME_LIGHT -> stringResource(R.string.settings_theme_light)
        AppSettings.THEME_DARK -> stringResource(R.string.settings_theme_dark)
        else -> stringResource(R.string.settings_theme_system)
    }

@Composable
private fun languageLabel(tag: String): String =
    when (tag) {
        LocaleManager.TAG_ZH -> stringResource(R.string.settings_language_zh)
        LocaleManager.TAG_EN -> stringResource(R.string.settings_language_en)
        LocaleManager.TAG_JA -> stringResource(R.string.settings_language_ja)
        LocaleManager.TAG_KO -> stringResource(R.string.settings_language_ko)
        LocaleManager.TAG_RU -> stringResource(R.string.settings_language_ru)
        else -> stringResource(R.string.settings_language_system)
    }

/**
 * 带丝滑展开动画的下拉菜单。
 *
 * 用户反馈模型下拉菜单「展开生硬」：默认的 popup 弹出缺少过渡。
 * 这里在菜单内容外套一层 200ms FastOutSlowInEasing 的
 * expandVertically + fadeIn，展开如轻推般顺滑；
 * 锚定与宽度匹配仍由外层 ExposedDropdownMenuBox + menuAnchor + exposedDropdownSize 保证。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmoothDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        AnimatedVisibility(
            visible = true,
            enter =
                expandVertically(
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Top,
                ) +
                    fadeIn(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)),
        ) {
            Column(content = content)
        }
    }
}
