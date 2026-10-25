package me.rerere.rikkahub.data.agent

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 给子代理准备工具集时真正要保证的事：白名单过滤（递归 / workflow / ask_user 永远不给）。
 *
 * 授权不再在这里剥：子代理拿到的是一张运行信封（PermissionContext.background()），
 * 信封内放行、信封外拒绝，不会停在 Pending 等人。
 */
class AgentToolFactoryTest {

    private fun tool(name: String) = Tool(
        name = name,
        description = "",
        parameters = { null },
        execute = { _: JsonElement -> emptyList<UIMessagePart>() },
    )

    private val allTools = listOf(
        tool("workspace_shell"),
        tool("workspace_read_file"),
        tool("job_start"),
        tool("search_web"),
        tool("ask_user"),
        tool("subagent_start"),
        tool("workflow_run"),
    )

    @Test
    fun policyStillFiltersRecursionWorkflowAndAskUser() {
        val prepared = prepareAgentTools(allTools, AgentToolGroup.entries.toSet(), excludeJobTools = false)
        val names = prepared.map { it.name }
        assertTrue("递归永远不给: " + names, names.none { it.startsWith("subagent") })
        assertTrue("workflow 永远不给: " + names, names.none { it.startsWith("workflow") })
        assertTrue("ask_user 永远不给（没有 UI 通道）", "ask_user" !in names)
    }

    @Test
    fun excludeJobToolsStillWorksThroughTheComposition() {
        val prepared = prepareAgentTools(
            allTools,
            setOf(AgentToolGroup.WORKSPACE),
            excludeJobTools = true,
        )
        assertEquals(
            "只剩工作区读写, job_* 被摘掉",
            listOf("workspace_shell", "workspace_read_file"),
            prepared.map { it.name },
        )
    }
}
