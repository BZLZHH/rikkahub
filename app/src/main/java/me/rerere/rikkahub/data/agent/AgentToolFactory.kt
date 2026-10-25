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
 * 授权不在这里做：子代理拿到的是一张运行信封（PermissionContext.background()），
 * 信封内放行、信封外拒绝；因为后台没人可问，绝不会停在 Pending 等人。
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
        return prepareAgentTools(all, groups, excludeJobTools)
    }
}

/**
 * 白名单过滤：只保留该组允许的工具。
 *
 * 抽成独立函数是为了能单独测 —— 上面那个方法要一个真的 ChatToolFactory（拖着半个 DI 图）才跑得起来，
 * 而这里要保证的语义只有一条：不该给的工具一个都不给（[AgentToolPolicy] 负责）。
 *
 * 授权不在这里做：子代理拿到的是一张运行信封，信封内放行、信封外拒绝。
 * 后台没人可问，所以不存在“停在 Pending 等人工确认”的死锁。
 */
internal fun prepareAgentTools(
    all: List<Tool>,
    groups: Set<AgentToolGroup>,
    excludeJobTools: Boolean,
): List<Tool> = AgentToolPolicy
    .filter(all, groups, excludeJobTools)
