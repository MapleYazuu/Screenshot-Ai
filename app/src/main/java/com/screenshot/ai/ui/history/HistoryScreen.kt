package com.screenshot.ai.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.screenshot.ai.R
import com.screenshot.ai.ai.AiProviderRegistry
import com.screenshot.ai.data.model.HistoryItem
import com.screenshot.ai.ui.common.EmptyState
import com.screenshot.ai.ui.common.InfoChip
import com.screenshot.ai.ui.common.MarkdownText
import com.screenshot.ai.ui.common.pressScaleWithHaptic
import com.screenshot.ai.ui.theme.PageHorizontalPadding
import com.screenshot.ai.util.TimeUtils
import java.io.File

/**
 * 提问历史界面。
 *
 * 列表卡片刻意不显示缩略图（避免滚动时解码大量图片导致卡顿），
 * 只有点击卡片原地展开后才加载完整截图。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val searchInput by viewModel.searchInput.collectAsStateWithLifecycle()
    val expandedIds by viewModel.expandedIds.collectAsStateWithLifecycle()
    val providerFilter by viewModel.providerFilter.collectAsStateWithLifecycle()
    val modelFilter by viewModel.modelFilter.collectAsStateWithLifecycle()
    val lastDeleted by viewModel.lastDeleted.collectAsStateWithLifecycle()
    val copied by viewModel.copied.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showFilterSheet by remember { mutableStateOf(false) }
    var fullscreenImage by remember { mutableStateOf<String?>(null) }

    // 复制成功提示
    LaunchedEffect(copied) {
        if (copied) {
            snackbarHostState.showSnackbar(context.getString(R.string.history_copied))
            viewModel.consumeCopied()
        }
    }

    // 删除后提供撤销
    LaunchedEffect(lastDeleted) {
        val deleted = lastDeleted ?: return@LaunchedEffect
        val result =
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.history_deleted),
                actionLabel = context.getString(R.string.history_undo),
            )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete()
        } else {
            viewModel.clearUndo()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    // 筛选按钮：带图标的小圆角按钮；有筛选条件时高亮主色
                    // 按压反馈：Ripple + 0.96 缩放 + 轻振动
                    val filterInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    FilledTonalButton(
                        onClick = { showFilterSheet = true },
                        modifier = Modifier.pressScaleWithHaptic(filterInteraction),
                        interactionSource = filterInteraction,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        colors =
                            if (providerFilter != null || modelFilter != null) {
                                ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            } else {
                                ButtonDefaults.filledTonalButtonColors()
                            },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.history_filter),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
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
                    .systemBarsPadding(),
        ) {
            // ---------------- 固定顶部搜索框（M3 圆角 + 浅灰底 + 聚焦主色边框） ----------------
            OutlinedTextField(
                value = searchInput,
                onValueChange = viewModel::onSearchChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PageHorizontalPadding, vertical = 8.dp),
                placeholder = {
                    Text(
                        text = stringResource(R.string.history_search_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                },
                trailingIcon = {
                    if (searchInput.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearSearch) {
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = stringResource(R.string.common_close),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    ),
            )

            // ---------------- 列表 ----------------
            if (state.items.isEmpty() && !state.loading) {
                EmptyState(
                    emoji = "🗂️",
                    title = stringResource(R.string.history_empty),
                    description = stringResource(R.string.history_empty_desc),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(
                            start = PageHorizontalPadding,
                            end = PageHorizontalPadding,
                            top = 4.dp,
                            bottom = 32.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(
                        items = state.items,
                        key = { _, item -> item.id },
                    ) { index, item ->
                        HistoryCard(
                            item = item,
                            expanded = expandedIds.contains(item.id),
                            // 轻微 stagger：仅对首屏可见项生效，避免长列表滚动时反复触发
                            staggerDelay = if (index < 8) index * 40 else 0,
                            onToggle = { viewModel.toggleExpand(item.id) },
                            onCopy = { viewModel.copyToClipboard(item) },
                            onDelete = { viewModel.delete(item) },
                            onReAsk = {
                                com.screenshot.ai.service.FloatingService
                                    .reAsk(context, item)
                            },
                            onShare = { viewModel.share(item) },
                            onOpenImage = { path -> fullscreenImage = path },
                        )
                    }
                }
            }
        }
    }

    // ---------------- 筛选面板 ----------------
    if (showFilterSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = sheetState,
            // 与全局规范一致：极浅灰底，避免纯白廉价感
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            FilterSheetContent(
                providers = state.providers,
                models = state.models,
                selectedProvider = providerFilter,
                selectedModel = modelFilter,
                onApply = { provider, model ->
                    viewModel.applyFilter(provider, model)
                    showFilterSheet = false
                },
                onReset = {
                    viewModel.resetFilter()
                    showFilterSheet = false
                },
            )
        }
    }

    // ---------------- 全屏查看截图 ----------------
    fullscreenImage?.let { path ->
        FullscreenImageDialog(path = path, onDismiss = { fullscreenImage = null })
    }
}

/**
 * 单条历史卡片。
 *
 * 收起态：相对时间 + Provider/模型标签 + 指令名 + 回复摘要（最多 2 行）
 * 展开态：完整 Prompt + 完整回复 + 完整截图
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(
    item: HistoryItem,
    expanded: Boolean,
    staggerDelay: Int,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onReAsk: () -> Unit,
    onShare: () -> Unit,
    onOpenImage: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(staggerDelay.toLong())
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // 展开/收起用 spring 平滑撑开（此前 tween 略显生硬）
                    .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                    .clip(RoundedCornerShape(20.dp))
                    .combinedClickable(
                        onClick = onToggle,
                        onLongClick = onShare,
                    ),
            // 统一高级感规范：20dp 圆角纯白卡片 + 4dp 柔和阴影
            shape = RoundedCornerShape(20.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // 顶行：时间加粗 + Provider/模型胶囊标签
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = TimeUtils.relativeTime(item.timestamp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    InfoChip(
                        text = AiProviderRegistry.find(item.providerId)?.displayName ?: item.providerId,
                    )
                    Spacer(Modifier.width(6.dp))
                    InfoChip(
                        text = item.modelId,
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = item.aiReply.ifBlank { "…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )

                // ---------------- 展开区域 ----------------
                // 布局顺序（3.1.0 调整）：截图置顶 → 完整 Prompt → 完整 AI 回复
                if (expanded) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(14.dp))

                    // 完整截图：置顶展示，点击可全屏（圆角 + 阴影，见 ScreenshotPreview）
                    ScreenshotPreview(
                        path = item.imagePath,
                        onClick = { onOpenImage(item.imagePath) },
                    )

                    Spacer(Modifier.height(14.dp))

                    LabeledText(
                        label = stringResource(R.string.history_label_prompt),
                        content = item.prompt,
                    )

                    Spacer(Modifier.height(12.dp))

                    // 完整 AI 回复：Markdown 富文本渲染（与悬浮窗同一套 Markwon）
                    Text(
                        text = stringResource(R.string.history_label_reply),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    MarkdownText(markdown = item.aiReply)

                    Spacer(Modifier.height(10.dp))

                    // 操作行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextAction(
                            icon = Icons.Rounded.Refresh,
                            label = stringResource(R.string.history_action_reask),
                            onClick = onReAsk,
                        )
                        TextAction(
                            icon = Icons.Rounded.ContentCopy,
                            label = stringResource(R.string.history_action_copy),
                            onClick = onCopy,
                        )
                        TextAction(
                            icon = Icons.Rounded.Share,
                            label = stringResource(R.string.history_action_share),
                            onClick = onShare,
                        )
                        TextAction(
                            icon = Icons.Rounded.Delete,
                            label = stringResource(R.string.history_action_delete),
                            onClick = onDelete,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledText(
    label: String,
    content: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 展开后展示的截图；文件缺失时给出明确提示而不是空白 */
@Composable
private fun ScreenshotPreview(
    path: String,
    onClick: () -> Unit,
) {
    val exists = remember(path) { path.isNotBlank() && File(path).exists() }

    Column {
        Text(
            text = stringResource(R.string.history_label_screenshot),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))

        if (!exists) {
            // 截图文件丢失/加载失败：灰色圆角占位图 + 提示文字，绝不崩溃
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .shadow(
                            elevation = 4.dp,
                            shape = RoundedCornerShape(12.dp),
                            clip = true,
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.history_image_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // 宽度撑满内容区，高度按原始比例自适应（FillWidth 保持纵横比，不拉伸变形），
            // 上限 320dp 防止超高截图占满屏幕；12dp 圆角 + 轻微阴影，点击全屏
            AsyncImage(
                model = File(path),
                contentDescription = stringResource(R.string.history_label_screenshot),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .shadow(
                            elevation = 6.dp,
                            shape = RoundedCornerShape(12.dp),
                            clip = true,
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onClick),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

@Composable
private fun TextAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    TextButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/** 底部筛选面板：Provider / 模型两级筛选 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FilterSheetContent(
    providers: List<Pair<String, String>>,
    models: List<Pair<String, String>>,
    selectedProvider: String?,
    selectedModel: String?,
    onApply: (String?, String?) -> Unit,
    onReset: () -> Unit,
) {
    var provider by remember { mutableStateOf(selectedProvider) }
    var model by remember { mutableStateOf(selectedModel) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PageHorizontalPadding)
                .padding(bottom = 28.dp),
    ) {
        Text(
            text = stringResource(R.string.history_filter_provider),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            providers.forEach { (id, name) ->
                FilterChip(
                    selected = provider == id,
                    onClick = { provider = if (provider == id) null else id },
                    label = { Text(name) },
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.history_filter_model),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            models.forEach { (id, name) ->
                FilterChip(
                    selected = model == id,
                    onClick = { model = if (model == id) null else id },
                    label = { Text(name) },
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.history_filter_reset))
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { onApply(provider, model) }) {
                Text(stringResource(R.string.history_filter_apply))
            }
        }
    }
}

/** 全屏查看截图 */
@Composable
private fun FullscreenImageDialog(
    path: String,
    onDismiss: () -> Unit,
) {
    // 关键修复：不用 androidx.compose.ui.window.Dialog。
    // 它在 Release 混淆后的非 Activity 宿主场景曾抛
    // 「CompositionLocal LocalLifecycleOwner not present」。
    // 改用自带 Lifecycle/ViewModel/SavedState 三大 Owner 的 LifecycleAwareDialog。
    com.screenshot.ai.ui.common.LifecycleAwareDialog(
        onDismissRequest = onDismiss,
        properties =
            androidx.compose.ui.window
                .DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.94f))
                    .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = File(path),
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
