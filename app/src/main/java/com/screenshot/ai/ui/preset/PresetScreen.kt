package com.screenshot.ai.ui.preset

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screenshot.ai.R
import com.screenshot.ai.data.model.Preset
import com.screenshot.ai.ui.common.EmptyState
import com.screenshot.ai.ui.common.InfoChip
import com.screenshot.ai.ui.common.SettingRow
import com.screenshot.ai.ui.common.pressScaleWithHaptic
import com.screenshot.ai.ui.theme.PageHorizontalPadding

/**
 * 预设指令管理界面。
 *
 * 交互：列表 + 右下角 FAB 新增；每条可编辑 / 删除 / 设为默认。
 * 编辑使用底部面板而非独立页面，保持操作路径短。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PresetScreen(
    viewModel: PresetViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current

    // 多选模式：selectionMode 开启后顶部变成「已选择 X 项 + 全选/取消/删除」
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var pendingDeleteMany by remember { mutableStateOf(false) }

    // 拖拽排序状态：dragIndex = 正在拖动的条目下标，dragOffset = 手指累计纵向位移
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }

    // 拖拽开始时被拖卡片的屏幕中心 Y（用于松手时按真实布局位置计算目标下标）
    var dragStartCenterY by remember { mutableStateOf(0f) }
    val lazyListState = rememberLazyListState()

    // 一次性消息映射为文案
    LaunchedEffect(message) {
        val text =
            when (message) {
                "EMPTY_CLIPBOARD" -> context.getString(R.string.preset_clipboard_empty)
                "DELETED" -> context.getString(R.string.preset_deleted)
                "DELETED_MANY" -> context.getString(R.string.preset_deleted_multi)
                "BUILTIN_LOCKED" -> context.getString(R.string.preset_builtin_hint)
                else -> null
            }
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedIds = emptySet()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selectionMode) {
                // 多选模式顶栏：已选数量 + 全选 / 退出 / 删除
                TopAppBar(
                    title = { Text(stringResource(R.string.preset_selected_count, selectedIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = { exitSelectionMode() }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.common_cancel),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            // 全选：只勾选可删除的自定义指令，内置指令永远不可选
                            selectedIds = presets.map { it.id }.toSet()
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.SelectAll,
                                contentDescription = stringResource(R.string.preset_select_all),
                            )
                        }
                        IconButton(
                            onClick = { pendingDeleteMany = true },
                            enabled = selectedIds.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.common_delete),
                                tint =
                                    if (selectedIds.isNotEmpty()) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    },
                            )
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.preset_title)) },
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
            }
        },
        floatingActionButton = {
            // 多选模式下隐藏 FAB，避免「新增」与「删除」入口打架
            if (!selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.newPreset() },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.preset_add)) },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .systemBarsPadding(),
        ) {
            if (presets.isEmpty()) {
                EmptyState(
                    emoji = "📋",
                    title = stringResource(R.string.preset_empty),
                )
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            start = PageHorizontalPadding,
                            end = PageHorizontalPadding,
                            top = 4.dp,
                            bottom = 96.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(items = presets, key = { _, preset -> preset.id }) { index, preset ->
                        // animateItemPlacement：条目删除 / 排序后，下方卡片平滑归位，不瞬间弹跳
                        Box(modifier = Modifier.animateItemPlacement(tween(durationMillis = 300))) {
                            val isSelected = preset.id in selectedIds
                            PresetCard(
                                preset = preset,
                                selectionMode = selectionMode,
                                selected = isSelected,
                                selectable = true,
                                modifier =
                                    Modifier
                                        .zIndex(if (dragIndex == index) 1f else 0f)
                                        .graphicsLayer {
                                            if (dragIndex == index) {
                                                translationY = dragOffset
                                                scaleX = 1.05f
                                                scaleY = 1.05f
                                                shadowElevation = 12.dp.toPx()
                                            } else {
                                                translationY = 0f
                                                scaleX = 1f
                                                scaleY = 1f
                                            }
                                        }
                                        // 多选模式：单击切换勾选；否则单击编辑
                                        // 长按（非多选模式）：进入多选模式并选中当前项
                                        .combinedClickable(
                                            onClick = {
                                                if (selectionMode) {
                                                    selectedIds =
                                                        if (isSelected) {
                                                            selectedIds - preset.id
                                                        } else {
                                                            selectedIds + preset.id
                                                        }
                                                } else {
                                                    viewModel.edit(preset)
                                                }
                                            },
                                            onLongClick = {
                                                if (!selectionMode) {
                                                    // 长按进入多选：稍强振动反馈
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    selectionMode = true
                                                    selectedIds = setOf(preset.id)
                                                }
                                            },
                                        )
                                        // 多选模式下禁用拖拽排序，退出后恢复
                                        .then(
                                            if (!selectionMode) {
                                                Modifier.pointerInput(preset.id) {
                                                    detectDragGesturesAfterLongPress(
                                                        onDragStart = {
                                                            dragIndex = index
                                                            dragOffset = 0f
                                                            // 记录被拖卡片的真实屏幕中心：松手时据此定位目标，
                                                            // 不依赖「估算高度 × 位移」的近似算法（那才是错位根源）
                                                            dragStartCenterY =
                                                                lazyListState.layoutInfo.visibleItemsInfo
                                                                    .firstOrNull { it.key == preset.id }
                                                                    ?.let { it.offset + it.size / 2f }
                                                                    ?: 0f
                                                        },
                                                        onDrag = { change, amount ->
                                                            change.consume()
                                                            dragOffset += amount.y
                                                        },
                                                        onDragEnd = {
                                                            val draggedCenter = dragStartCenterY + dragOffset
                                                            val target =
                                                                lazyListState.layoutInfo.visibleItemsInfo
                                                                    .filter { it.key != preset.id }
                                                                    .minByOrNull {
                                                                        kotlin.math.abs(
                                                                            (it.offset + it.size / 2f) - draggedCenter,
                                                                        )
                                                                    }
                                                                    ?.index
                                                                    ?: index
                                                            viewModel.movePreset(
                                                                index,
                                                                target.coerceIn(0, presets.lastIndex),
                                                            )
                                                            dragIndex = null
                                                            dragOffset = 0f
                                                        },
                                                        onDragCancel = {
                                                            dragIndex = null
                                                            dragOffset = 0f
                                                        },
                                                    )
                                                }
                                            } else {
                                                Modifier
                                            },
                                        ),
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------- 编辑面板 ----------------
    editor?.let { state ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeEditor() },
            sheetState = sheetState,
            // 微灰背景（浅色 #F8F9FA / 深色主题背景），避免纯白廉价感
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            PresetEditorContent(
                state = state,
                isNew = state.isNew,
                onNameChange = { value ->
                    viewModel.updateEditor { it.copy(name = value, nameError = null) }
                },
                onPromptChange = { value ->
                    viewModel.updateEditor { it.copy(prompt = value, promptError = null) }
                },
                onIconChange = { value ->
                    viewModel.updateEditor { it.copy(icon = value.take(2)) }
                },
                onDefaultChange = { value ->
                    viewModel.updateEditor { it.copy(isDefault = value) }
                },
                onImportClipboard = { viewModel.importFromClipboard() },
                onSave = { viewModel.save() },
                onCancel = { viewModel.closeEditor() },
            )
        }
    }

    // ---------------- 批量删除确认（Material 3 确认对话框） ----------------
    if (pendingDeleteMany) {
        AlertDialog(
            onDismissRequest = { pendingDeleteMany = false },
            title = { Text(stringResource(R.string.preset_delete_title)) },
            text = {
                Text(stringResource(R.string.preset_delete_selected_message, selectedIds.size))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePresets(selectedIds)
                    pendingDeleteMany = false
                    exitSelectionMode()
                }) {
                    Text(
                        text = stringResource(R.string.preset_confirm_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteMany = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * 单条指令卡片。
 *
 * 交互（重构后）：
 * - 普通模式：单击编辑（内置指令锁定，点击只提示不可修改）；
 *   长按拖动 = 拖拽排序（顺序即优先级，首项即默认指令）；
 *   长按不动 = 进入多选模式并选中本项；
 * - 多选模式：单击切换勾选（内置指令复选框禁用、不可删除），拖拽禁用，
 *   顶部「全选 / 取消 / 删除」操作栏统一管理。
 */
@Composable
private fun PresetCard(
    preset: Preset,
    selectionMode: Boolean,
    selected: Boolean,
    selectable: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // 图标：用户未设置时用 Emoji 占位，保证视觉一致
            Text(
                text = preset.icon ?: "💬",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (preset.isDefault) {
                        Spacer(Modifier.width(8.dp))
                        InfoChip(
                            text = stringResource(R.string.preset_default_badge),
                            container = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = preset.prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(10.dp))

            if (selectionMode) {
                // 多选模式：右侧复选框（内置指令禁用——系统指令不可删除）
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    enabled = selectable,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            } else {
                // 拖拽把手：纯视觉提示，实际手势挂在整卡上
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
                    contentDescription = stringResource(R.string.preset_reorder_hint),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier =
                        Modifier
                            .size(22.dp)
                            .align(Alignment.CenterVertically),
                )
            }
        }
    }
}

/** 编辑面板内容 */
@Composable
private fun PresetEditorContent(
    state: PresetViewModel.EditorState,
    isNew: Boolean,
    onNameChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onIconChange: (String) -> Unit,
    onDefaultChange: (Boolean) -> Unit,
    onImportClipboard: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    // 弹窗出现动画：200ms 缩放 + 渐入（scale 0.95 -> 1，alpha 0 -> 1）
    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        enter =
            androidx.compose.animation.fadeIn(animationSpec = tween(200)) +
                androidx.compose.animation.scaleIn(
                    initialScale = 0.95f,
                    animationSpec = tween(200),
                ),
    ) {
        // 弹窗内容占屏幕宽度 90% 并居中，解决「又窄又小」的局促感
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth(0.9f)
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        // 内部呼吸感：整体 24dp 内边距
                        .padding(24.dp)
                        .padding(bottom = 28.dp),
            ) {
            // 标题：加大加粗，与输入框拉开 20dp
            Text(
                text = stringResource(if (isNew) R.string.preset_add else R.string.preset_edit),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp),
                label = { Text(stringResource(R.string.preset_field_name)) },
                singleLine = true,
                isError = state.nameError != null,
                supportingText =
                    state.nameError?.let {
                        { Text(stringResource(R.string.preset_error_name_empty)) }
                    },
                // 统一 16dp 圆角 + M3 默认聚焦主色边框
                // 12dp 圆角 + 纯白底 + 柔和边框（聚焦主色边框由 M3 默认）
                shape = RoundedCornerShape(12.dp),
                colors =
                    androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    ),
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.icon,
                onValueChange = onIconChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp),
                label = { Text(stringResource(R.string.preset_field_icon)) },
                singleLine = true,
                // 12dp 圆角 + 纯白底 + 柔和边框（聚焦主色边框由 M3 默认）
                shape = RoundedCornerShape(12.dp),
                colors =
                    androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    ),
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.prompt,
                onValueChange = onPromptChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                label = { Text(stringResource(R.string.preset_field_prompt)) },
                isError = state.promptError != null,
                supportingText =
                    state.promptError?.let {
                        { Text(stringResource(R.string.preset_error_prompt_empty)) }
                    },
                // 12dp 圆角 + 纯白底 + 柔和边框（聚焦主色边框由 M3 默认）
                shape = RoundedCornerShape(12.dp),
                colors =
                    androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    ),
            )

            Spacer(Modifier.height(12.dp))

            TextButton(onClick = onImportClipboard) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.preset_import_clipboard))
            }

            Spacer(Modifier.height(8.dp))

            SettingRow(
                title = stringResource(R.string.preset_set_default),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                trailing = {
                    Switch(checked = state.isDefault, onCheckedChange = onDefaultChange)
                },
            )

            Spacer(Modifier.height(16.dp))

            // 按钮水平排布、宽度等分、间距 12dp：取消（描边）| 保存（实心主色）
            // 按压反馈：Ripple（M3 自带）+ 0.96 缩放 + 轻振动
            val cancelInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val saveInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier =
                        Modifier
                            .weight(1f)
                            .pressScaleWithHaptic(cancelInteraction),
                    interactionSource = cancelInteraction,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.preset_cancel))
                }
                Button(
                    onClick = onSave,
                    modifier =
                        Modifier
                            .weight(1f)
                            .pressScaleWithHaptic(saveInteraction),
                    interactionSource = saveInteraction,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.preset_save))
                }
            }
            }
        }
    }
}
