package com.screenshot.ai.ui.preset

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.screenshot.ai.ServiceLocator
import com.screenshot.ai.data.model.Preset
import com.screenshot.ai.data.prefs.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 预设指令页 ViewModel。
 *
 * 编辑态使用 [EditorState] 描述：null 表示未打开编辑面板，
 * 非 null 时 id 为空字符串表示「新建」，否则为「编辑既有指令」。
 */
class PresetViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = ServiceLocator.presetRepository

    /** 编辑面板状态 */
    data class EditorState(
        val id: String = "",
        val name: String = "",
        val prompt: String = "",
        val icon: String = "",
        val isDefault: Boolean = false,
        val builtin: Boolean = false,
        val nameError: String? = null,
        val promptError: String? = null,
    ) {
        val isNew: Boolean get() = id.isBlank()
    }

    private val _editor = MutableStateFlow<EditorState?>(null)
    val editor: StateFlow<EditorState?> = _editor.asStateFlow()

    /** 一次性消息（例如剪贴板为空） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val presets: StateFlow<List<Preset>> =
        repository.observeAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val settings: StateFlow<AppSettings> =
        ServiceLocator.settingsRepository.settings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AppSettings(),
        )

    /** 新建指令 */
    fun newPreset() {
        _editor.value = EditorState()
    }

    /** 编辑既有指令（3.1.0 起无内置指令，任何指令都可编辑） */
    fun edit(preset: Preset) {
        _editor.value =
            EditorState(
                id = preset.id,
                name = preset.name,
                prompt = preset.prompt,
                icon = preset.icon.orEmpty(),
                isDefault = preset.isDefault,
                builtin = preset.builtin,
            )
    }

    /** 关闭编辑面板 */
    fun closeEditor() {
        _editor.value = null
    }

    /** 编辑字段变更 */
    fun updateEditor(transform: (EditorState) -> EditorState) {
        _editor.value = _editor.value?.let(transform)
    }

    /** 从剪贴板导入 Prompt 内容 */
    fun importFromClipboard() {
        val context: Context = getApplication()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text =
            clipboard
                ?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                ?.trim()

        if (text.isNullOrBlank()) {
            _message.value = "EMPTY_CLIPBOARD"
            return
        }
        updateEditor { state ->
            state.copy(
                prompt = if (state.prompt.isBlank()) text else "${state.prompt}\n$text",
                promptError = null,
            )
        }
    }

    /** 保存 */
    fun save() {
        val state = _editor.value ?: return
        val name = state.name.trim()
        val prompt = state.prompt.trim()

        val nameError = if (name.isEmpty()) "EMPTY_NAME" else null
        val promptError = if (prompt.isEmpty()) "EMPTY_PROMPT" else null
        if (nameError != null || promptError != null) {
            _editor.value = state.copy(nameError = nameError, promptError = promptError)
            return
        }

        viewModelScope.launch {
            repository.upsert(
                Preset(
                    id = state.id, // 空字符串由仓库生成 UUID
                    name = name,
                    prompt = prompt,
                    icon = state.icon.trim().ifBlank { null },
                    isDefault = state.isDefault,
                    builtin = state.builtin,
                ),
            )
            // 若勾选了默认，同步写入 DataStore，保证悬浮快捷提问立即生效
            if (state.isDefault) {
                val saved = repository.getDefault()
                if (saved != null) {
                    ServiceLocator.settingsRepository.setDefaultPreset(saved.id)
                }
            }
            _editor.value = null
        }
    }

    /** 删除 */
    fun delete(preset: Preset) {
        viewModelScope.launch {
            repository.delete(preset.id)
            _message.value = "DELETED"
        }
    }

    /** 批量删除（多选模式）：内置指令由仓库层自动跳过 */
    fun deletePresets(ids: Set<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.deleteMany(ids)
            _message.value = "DELETED_MANY"
        }
    }

    /** 设为默认 */
    fun setDefault(preset: Preset) {
        viewModelScope.launch {
            repository.setDefault(preset.id)
            ServiceLocator.settingsRepository.setDefaultPreset(preset.id)
        }
    }

    /**
     * 拖拽排序：把 from 位置的指令移动到 to 位置。
     * 顺序即优先级：仓库会在移动后把列表首项设为默认（截图时默认使用）。
     */
    fun movePreset(
        from: Int,
        to: Int,
    ) {
        if (from == to) return
        viewModelScope.launch { repository.reorder(from, to) }
    }

    /** 内置指令不可修改：点击时提示（编辑入口已对内置项隐藏） */
    fun notifyBuiltinLocked() {
        _message.value = "BUILTIN_LOCKED"
    }

    fun consumeMessage() {
        _message.value = null
    }
}
