package me.rerere.rikkahub.data.event

import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

sealed class AppEvent {
    data class Speak(val text: String) : AppEvent()
    data object OpenUsageAccessSettings : AppEvent()

    /** 聊天生成过程中的流式更新，由 ChatNotificationManager 消费用于 Live Update 通知。 */
    data class ChatGenerationUpdate(
        val conversationId: Uuid,
        val lastMessage: UIMessage,
        val senderName: String,
    ) : AppEvent()

    /**
     * 聊天生成结束（完成、失败或取消）。
     * [contentPreview] 为 null 时仅取消 Live Update 通知，不发送完成通知。
     */
    /** 后台任务结束（成功/失败/被杀/超时/中断）。 */
    data class WorkspaceJobFinished(
        val jobId: String,
        val workspaceId: String,
        val defId: String?,
        val name: String,
        val status: String,
        val exitCode: Int?,
        val runtimeMs: Long?,
        val conversationId: String?,
        val autoWake: Boolean,
        val notify: Boolean,
    ) : AppEvent()

    /** 定时任务因系统限制无法在后台执行, 已记为延后。 */
    data class WorkspaceJobDeferred(
        val jobId: String,
        val workspaceId: String,
        val name: String,
    ) : AppEvent()

    data class ChatGenerationEnded(
        val conversationId: Uuid,
        val senderName: String,
        val contentPreview: String?,
    ) : AppEvent()
}
