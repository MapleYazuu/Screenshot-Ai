package com.screenshot.ai.ai

/**
 * 一条对话消息。
 *
 * 图片以 Base64（不含 data: 前缀）随消息携带，用于多模态模型。
 * 追问时会保留首轮图片，从而维持「同一张截图」的上下文。
 *
 * @param role    角色：system / user / assistant
 * @param text    文本内容
 * @param imageBase64 可选的图片 Base64（仅 user 消息会带图）
 * @param imageMimeType 图片 MIME 类型，默认 image/jpeg
 */
data class ChatMessage(
    val role: Role,
    val text: String,
    val imageBase64: String? = null,
    val imageMimeType: String = "image/jpeg",
) {
    enum class Role(
        val wireName: String,
    ) {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant"),
    }

    /** 是否包含图片 */
    val hasImage: Boolean get() = !imageBase64.isNullOrEmpty()

    companion object {
        /** 构造系统提示词 */
        fun system(text: String) = ChatMessage(Role.SYSTEM, text)

        /** 构造纯文本用户消息 */
        fun user(text: String) = ChatMessage(Role.USER, text)

        /** 构造带图片的用户消息 */
        fun userWithImage(
            text: String,
            imageBase64: String,
            mimeType: String = "image/jpeg",
        ) = ChatMessage(Role.USER, text, imageBase64, mimeType)

        /** 构造助手消息（用于多轮上下文） */
        fun assistant(text: String) = ChatMessage(Role.ASSISTANT, text)
    }
}
