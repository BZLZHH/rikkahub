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
    /** 后台任务开始运行。 */
    data class WorkspaceJobStarted(
        val jobId: String,
        val workspaceId: String,
        val name: String,
    ) : AppEvent()

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

    /**
     * AI 派出去的执行体（子代理 / workflow）结束。
     *
     * 与 [WorkspaceJobFinished] 分开是因为两者的"进度"形状不同（这边是脚本与 agent, 那边是进程与退出码）;
     * 但通知与唤醒的诉求完全一样, 所以消费侧是同一个管理器。
     *
     * 注意: 在此之前**两个都没有人发这个事件** —— `RunOrchestrator.onRunFinished` 一直是默认空实现,
     * 于是子代理的 `notify` / `autoWake` 两个字段虽然一路从工具传到了数据库, 却从来没有人消费。
     */
    data class BackgroundRunFinished(
        val runId: String,
        /** true = workflow, false = 子代理 */
        val isWorkflow: Boolean,
        val title: String,
        val status: String,
        val runtimeMs: Long?,
        val conversationId: String?,
        val notify: Boolean,
        val autoWake: Boolean,
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
