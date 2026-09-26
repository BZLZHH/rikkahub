package me.rerere.rikkahub.data.agent

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 给子代理准备工具集时真正要保证的两件事。
 *
 * 这里测的是 [prepareAgentTools] 而不是整个 `AgentToolFactory`: 后者要一个真的 `ChatToolFactory`
 * （拖着半个 DI 图）才跑得起来, 而它的全部语义就是这两条。
 *
 * 第二条尤其要紧 —— 子代理没有 UI 通道, 只要有一个工具停在 Pending 等人工确认,
 * 那个子代理就**永远醒不过来**。所以"过滤后一律免审批"必须逐条成立, 不能有例外。
 */
class AgentToolFactoryTest {

    private fun tool(name: String, needsApproval: Boolean = false) = Tool(
        name = name,
        description = "",
        parameters = { null },
        needsApproval = { needsApproval },
        execute = { _: JsonElement -> emptyList<UIMessagePart>() },
    )

    private val allTools = listOf(
        // 默认就要求审批的 —— 正是最容易被漏掉的那一类
        tool("workspace_shell", needsApproval = true),
        tool("workspace_read_file"),
        tool("job_start", needsApproval = true),
        tool("search_web"),
        tool("ask_user", needsApproval = true),
        tool("subagent_start", needsApproval = true),
        tool("workflow_run", needsApproval = true),
    )

    @Test
    fun everySurvivingToolIsApprovalFree() {
        val prepared = prepareAgentTools(allTools, AgentToolGroup.entries.toSet(), excludeJobTools = false)
        assertTrue("至少要留下几个工具, 否则这条断言没意义: " + prepared.map { it.name }, prepared.isNotEmpty())
        prepared.forEach { tool ->
            assertTrue(
                "子代理没有 UI 通道, " + tool.name + " 若还要审批就是死锁",
                !tool.needsApproval(JsonNull),
            )
        }
    }

    @Test
    fun approvalFreeAppliesEvenToToolsThatNormallyNeedIt() {
        // 这一条是这个测试的重点: 原来需要审批的工具被留下时, 也必须被改成免审批
        val prepared = prepareAgentTools(
            allTools,
            setOf(AgentToolGroup.WORKSPACE),
            excludeJobTools = false,
        )
        val shell = prepared.first { it.name == "workspace_shell" }
        assertTrue(
            "workspace_shell 本来是 needsApproval = true 的",
            allTools.first { it.name == "workspace_shell" }.needsApproval(JsonNull),
        )
        assertTrue("到了子代理手里必须变成免审批", !shell.needsApproval(JsonNull))
    }

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
