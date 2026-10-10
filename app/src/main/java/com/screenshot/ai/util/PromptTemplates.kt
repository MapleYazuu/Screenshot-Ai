package com.screenshot.ai.util

import android.content.Context
import com.screenshot.ai.data.model.Preset

/**
 * 提问文本模板。
 *
 * 为什么把指令模板放到代码里而不是 strings.xml：
 * 这些模板是**发给 AI 的 Meta-Instruction**，不是给用户看的界面文案。
 * 它们的正确性标准是「模型能否稳定遵循」，而不是「本地化是否地道」，
 * 因此统一用英文维护（英文元指令的模型遵循度最稳定），
 * 再通过 [withReplyLanguageDirective] 显式约束目标输出语言。
 *
 * 这样设计的直接好处：
 * - 内置预设的提示词只需要一份英文版本，不必随界面语言维护 5 份翻译；
 * - 输出语言由「独立的一行显式指令」保证，而不是依赖提示词本身的语种。
 */
object PromptTemplates {

    /**
     * 系统提示词。
     *
     * 约束输出风格（先结论后要点、不复述指令、长文本用 Markdown），
     * 但不在这里写死输出语言 —— 语言由 [replyLanguageDirective] 单独控制，
     * 避免「系统提示说中文、用户要日语」这种自相矛盾。
     */
    const val SYSTEM_PROMPT: String =
        "You are an Android screenshot analysis assistant. The user sends you a phone screenshot " +
            "and an instruction; answer the instruction directly based on what is visible in the image. " +
            "Requirements: " +
            "1) Lead with the conclusion, then list supporting points in a clear structure. " +
            "2) Do not restate or paraphrase the user's instruction. " +
            "3) No greetings, no filler, nothing unrelated to the screenshot. " +
            "4) Use Markdown headings or lists when the answer is long."

    /**
     * 回复语言指令。
     *
     * 单独成行、放在提问文本最末尾：位置越靠后，模型对格式与语言约束的遵循度越高
     * （这是长上下文场景下的通行经验）。
     */
    fun replyLanguageDirective(languageCode: String = LocaleManager.currentLanguage()): String {
        val name = LocaleManager.aiReplyLanguageName(languageCode)
        return "Reply in $name."
    }

    /**
     * 把回复语言指令追加到提问文本末尾。
     *
     * 幂等：同一句话不会因为被调用两次而重复追加。
     * 这一点很重要 —— 内置预设、用户自定义预设、OCR 降级路径都可能各自调用一次。
     */
    fun withReplyLanguageDirective(
        prompt: String,
        languageCode: String = LocaleManager.currentLanguage()
    ): String {
        val directive = replyLanguageDirective(languageCode)
        if (prompt.contains(directive)) return prompt
        return if (prompt.isBlank()) directive else "$prompt\n\n$directive"
    }

    /**
     * 把截图 OCR 出来的文字包装成提问文本（非视觉模型的降级路径）。
     */
    fun withOcrText(
        prompt: String,
        ocrText: String,
        languageCode: String = LocaleManager.currentLanguage()
    ): String {
        val body = "[Text extracted from the screenshot by on-device OCR]\n$ocrText"
        return withReplyLanguageDirective("$prompt\n\n$body", languageCode)
    }

    /**
     * 把自定义指令的显示名与提示词解析成最终发给模型的内容。
     *
     * 处理两件事：
     * 1. 内置指令的提示词模板存在代码里（[Preset.builtinPromptRes]），
     *    这里按当前界面语言取对应翻译版本；用户自建指令直接用其 prompt 字段；
     * 2. 统一追加回复语言指令。
     */
    fun effectivePrompt(context: Context, preset: Preset): String {
        val raw = resolveBuiltinTemplate(context, preset) ?: preset.prompt
        return withReplyLanguageDirective(raw)
    }

    /** 取内置指令被本地化后的提示词模板；非内置或找不到资源时返回 null */
    private fun resolveBuiltinTemplate(context: Context, preset: Preset): String? {
        val resId = preset.builtinPromptRes
        if (!preset.builtin || resId == 0) return null
        return runCatching { context.getString(resId) }.getOrNull()
    }
}
