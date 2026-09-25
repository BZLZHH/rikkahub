package me.rerere.rikkahub.data.agent

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.ai.provider.Model

/**
 * 组装子代理的工具集。
 *
 * 两步:
 * 1. 用主会话那套 [ChatToolFactory] 拿到完整工具集（复用现有实现, 不另造）;
 * 2. 按 [AgentToolPolicy] 的白名单过滤掉不该给子代理的（递归 / workflow / 人工交互）。
 *
 * **关键**: 过滤后的工具一律 [Tool.copy] 成"不需要审批"。
 * 原因: 子代理没有 UI 通道, 工具若停在 Pending 等人工确认就是**死锁**。
 * 放行它是安全的 —— 能调用什么已经被白名单定死了, 而且父代理为 `subagent_start`
 * 本身已经过了一次人工审批（或用户的自动审批）。
 */
class AgentToolFactory(
    private val chatToolFactory: ChatToolFactory,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        groups: Set<AgentToolGroup>,
        excludeJobTools: Boolean,
        cwd: String?,
        conversationId: String?,
    ): List<Tool> {
        val all = chatToolFactory.createTools(
            settings = settings,
            assistant = assistant,
            model = model,
            workspaceCwd = cwd,
            conversationId = conversationId,
        )
        return AgentToolPolicy
            .filter(all, groups, excludeJobTools)
            .map { it.copy(needsApproval = { false }) }
    }
}
