package com.screenshot.ai.ui.history

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.screenshot.ai.R
import com.screenshot.ai.ServiceLocator
import com.screenshot.ai.data.model.HistoryItem
import com.screenshot.ai.data.prefs.AppSettings
import com.screenshot.ai.data.repo.HistoryFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 历史页状态。
 *
 * @param items      当前筛选结果（按时间倒序）
 * @param loading    是否仍在等待首帧数据
 * @param providers  用于筛选面板的 Provider 选项
 * @param models     用于筛选面板的模型选项
 */
data class HistoryUiState(
    val items: List<HistoryItem> = emptyList(),
    val loading: Boolean = true,
    val providers: List<Pair<String, String>> = emptyList(),
    val models: List<Pair<String, String>> = emptyList(),
)

/**
 * 历史页 ViewModel。
 *
 * 搜索实现要点：
 * - 输入即搜，但先经过 300ms 防抖，避免每敲一个字都重新过滤整表；
 * - 搜索与筛选条件合并为同一个 [HistoryFilter]，由仓库统一处理，
 *   因而「搜索范围 + 筛选叠加」的逻辑只有一份实现。
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HistoryViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = ServiceLocator.historyRepository

    /** 搜索关键词原始输入（防抖前） */
    private val _searchInput = MutableStateFlow("")
    val searchInput: StateFlow<String> = _searchInput.asStateFlow()

    /** 当前筛选条件（Provider / 模型） */
    private val _providerFilter = MutableStateFlow<String?>(null)
    val providerFilter: StateFlow<String?> = _providerFilter.asStateFlow()

    private val _modelFilter = MutableStateFlow<String?>(null)
    val modelFilter: StateFlow<String?> = _modelFilter.asStateFlow()

    /** 展开的卡片 id 集合（支持多张同时展开） */
    private val _expandedIds = MutableStateFlow<Set<Long>>(emptySet())
    val expandedIds: StateFlow<Set<Long>> = _expandedIds.asStateFlow()

    /** 最近一次删除，用于 Snackbar 撤销 */
    private val _lastDeleted = MutableStateFlow<HistoryItem?>(null)
    val lastDeleted: StateFlow<HistoryItem?> = _lastDeleted.asStateFlow()

    /** 复制成功的一次性事件，UI 弹完提示后调用 [consumeCopied] */
    private val _copied = MutableStateFlow(false)
    val copied: StateFlow<Boolean> = _copied.asStateFlow()

    fun consumeCopied() {
        _copied.value = false
    }

    /** 全局配置（主题模式） */
    val settings: StateFlow<AppSettings> =
        ServiceLocator.settingsRepository.settings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AppSettings(),
        )

    val uiState: StateFlow<HistoryUiState> =
        combine(
            _searchInput.debounce(SEARCH_DEBOUNCE_MS),
            _providerFilter,
            _modelFilter,
        ) { query, provider, model ->
            HistoryFilter(query = query.trim(), providerId = provider, modelId = model)
        }.flatMapLatest { filter ->
            repository.observeFiltered(filter)
        }.combine(repository.observeAll()) { filtered, all ->
            HistoryUiState(
                items = filtered,
                loading = false,
                // 筛选项来源于全量数据，避免筛选后选项自己消失
                providers =
                    all.map { it.providerId }.distinct().sorted().map { id ->
                        id to (
                            com.screenshot.ai.ai.AiProviderRegistry
                                .find(id)
                                ?.displayName ?: id
                        )
                    },
                models =
                    all
                        .map { it.modelId }
                        .distinct()
                        .sorted()
                        .map { it to it },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryUiState(),
        )

    /** 搜索输入 */
    fun onSearchChange(value: String) {
        _searchInput.value = value
    }

    /** 清空搜索 */
    fun clearSearch() {
        _searchInput.value = ""
    }

    /** 应用筛选（来自底部筛选面板） */
    fun applyFilter(
        providerId: String?,
        modelId: String?,
    ) {
        _providerFilter.value = providerId
        _modelFilter.value = modelId
    }

    /** 重置筛选 */
    fun resetFilter() {
        _providerFilter.value = null
        _modelFilter.value = null
    }

    /** 展开 / 收起某条记录（原地展开，无跳转） */
    fun toggleExpand(id: Long) {
        _expandedIds.value =
            _expandedIds.value.let { current ->
                if (current.contains(id)) current - id else current + id
            }
    }

    /** 删除并记录以便撤销 */
    fun delete(item: HistoryItem) {
        viewModelScope.launch {
            _lastDeleted.value = item
            repository.delete(item)
        }
    }

    /** 撤销删除 */
    fun undoDelete() {
        val item = _lastDeleted.value ?: return
        viewModelScope.launch {
            repository.restore(item)
            _lastDeleted.value = null
        }
    }

    /** 撤销提示已消失 */
    fun clearUndo() {
        _lastDeleted.value = null
    }

    /** 复制 Prompt + 回复到剪贴板 */
    fun copyToClipboard(item: HistoryItem) {
        val context = getApplication<Application>()
        // 标签文案走字符串资源：复制内容也会展示给用户，不能写死中文
        val presetLabel = context.getString(R.string.history_clipboard_label_preset)
        val promptLabel = context.getString(R.string.history_clipboard_label_prompt)
        val text =
            buildString {
                appendLine("$presetLabel${item.presetName}")
                appendLine("$promptLabel${item.prompt}")
                appendLine()
                append(item.aiReply)
            }
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("screenshot-ai", text))
        // 只在真正写入成功后才提示，避免无剪贴板服务时给出错误反馈
        _copied.value = clipboard != null
    }

    /** 分享这条记录（文本） */
    fun share(item: HistoryItem) {
        val context = getApplication<Application>()
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, item.title)
                putExtra(Intent.EXTRA_TEXT, "${item.prompt}\n\n${item.aiReply}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.history_share_chooser))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        /** 搜索防抖时间：300ms 是「输入即搜」与性能的平衡点 */
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
