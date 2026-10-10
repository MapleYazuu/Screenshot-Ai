package com.screenshot.ai.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screenshot.ai.R
import com.screenshot.ai.data.model.CustomModelConfig
import com.screenshot.ai.ui.common.SettingRow
import com.screenshot.ai.ui.common.pressScaleWithHaptic
import com.screenshot.ai.ui.theme.PageHorizontalPadding

/**
 * 「其他 / 自定义模型」卡片。
 *
 * 视觉规范与内置 Provider 卡片完全一致（见 ProviderCard 的注释）：
 * - 纯白卡面 + 2dp 阴影 + 16dp 圆角，展开/收起不变色；
 * - 高度动画只由 animateContentSize(300ms FastOutSlowIn) 驱动，
 *   内容用 fadeIn(延迟 80ms) 渐显；
 * - 输入区嵌套 #F5F5F5 浅灰小卡片（12dp 圆角、16dp 内边距）；
 * - 保存 = 实心主色、测试连接 = 描边，权重 1:1、间距 12dp。
 *
 * 配置表单：显示名称 / Base URL / 模型 ID / API Key（可留空）/ 支持图片输入 Switch。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomModelCard(
    viewModel: SettingsViewModel,
    config: CustomModelConfig,
    onExpandedChanged: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(config.displayName) }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var modelId by remember { mutableStateOf(config.modelId) }
    var vision by remember { mutableStateOf(config.supportsVision) }
    var apiKey by remember { mutableStateOf(viewModel.customApiKey()) }
    var keyVisible by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val testState by viewModel.customTestState.collectAsStateWithLifecycle()

    // 测试成功：Toast「连接成功」明确反馈（成功标志只在本次测试成功后短暂为 true）
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(testState.succeeded) {
        if (testState.succeeded) {
            android.widget.Toast
                .makeText(context, R.string.settings_test_ok, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    // 保存成功后 DataStore 回传规范化配置，本地字段同步一次
    LaunchedEffect(config) {
        name = config.displayName
        baseUrl = config.baseUrl
        modelId = config.modelId
        vision = config.supportsVision
    }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PageHorizontalPadding, vertical = 6.dp)
                .animateContentSize(tween(durationMillis = 300, easing = FastOutSlowInEasing)),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        SettingRow(
            title = config.displayName.ifBlank { stringResource(R.string.settings_custom_model) },
            subtitle =
                if (config.isConfigured) {
                    config.normalizedModelId()
                } else {
                    stringResource(R.string.settings_custom_model_hint)
                },
            modifier =
                Modifier.clickable {
                    expanded = !expanded
                    onExpandedChanged(expanded)
                },
            // 右侧「+」入口，与需求一致；点击卡片任意位置都能展开
            trailing = {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.settings_custom_model),
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = 80)),
            exit = fadeOut(animationSpec = tween(durationMillis = 140)),
        ) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
                Spacer(Modifier.height(4.dp))

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(inputAreaColor())
                            .padding(16.dp),
                ) {
                    Column {
                        // 显示名称
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.custom_field_name)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                        )

                        Spacer(Modifier.height(16.dp))

                        // Base URL（留空 = 标准 OpenAI 地址；缺协议自动补 https://）
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.custom_field_base_url)) },
                            placeholder = { Text("https://openrouter.ai/api/v1") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            supportingText = { Text(stringResource(R.string.custom_url_hint)) },
                        )

                        Spacer(Modifier.height(16.dp))

                        // API Key（可留空）
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
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

                        Spacer(Modifier.height(16.dp))

                        // 模型 ID（必填：保存与调用都以此为准）
                        OutlinedTextField(
                            value = modelId,
                            onValueChange = { modelId = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.custom_field_model_id)) },
                            placeholder = { Text("gpt-4o / claude-3-5-sonnet / llama3") },
                            singleLine = true,
                            isError = localError != null,
                            shape = RoundedCornerShape(12.dp),
                        )

                        Spacer(Modifier.height(16.dp))

                        // 支持图片输入 Switch（真实控制截图走 Base64 还是本地 OCR 降级）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.custom_field_vision),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(R.string.custom_vision_off_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(checked = vision, onCheckedChange = { vision = it })
                        }

                        Spacer(Modifier.height(16.dp))

                        // 保存（实心主色） / 测试连接（描边），权重 1:1、间距 12dp
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
                                    if (modelId.isBlank()) {
                                        localError = "custom_error_model_id"
                                    } else {
                                        localError = null
                                        viewModel.saveCustomModel(name, baseUrl, modelId, apiKey, vision)
                                        viewModel.resetCustomTestState()
                                    }
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
                                onClick = {
                                    localError = null
                                    viewModel.testCustomModel(name, baseUrl, modelId, apiKey, vision)
                                },
                                enabled = !testState.testing,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .pressScaleWithHaptic(testInteraction),
                                interactionSource = testInteraction,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                if (testState.testing) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    stringResource(
                                        if (testState.testing) {
                                            R.string.settings_testing
                                        } else {
                                            R.string.settings_test_connection
                                        },
                                    ),
                                )
                            }
                        }

                        // 校验与测试结果反馈：清晰展示错误，不崩溃、不静默
                        val errorText =
                            when {
                                localError == "custom_error_model_id" -> stringResource(R.string.custom_error_model_id)
                                testState.error != null -> testState.error
                                else -> null
                            }
                        if (errorText != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = errorText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else if (!testState.testing && testState.succeeded) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.settings_test_ok),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
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
