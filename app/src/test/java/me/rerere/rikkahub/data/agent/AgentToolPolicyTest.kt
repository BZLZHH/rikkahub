package me.rerere.rikkahub.data.agent

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具白名单是子代理的**安全边界**, 所以这里逐条锁住: 哪些组会给、哪些名字永远不给。
 */
class AgentToolPolicyTest {

    private fun tool(name: String) = Tool(
        name = name,
        description = "",
        parameters = { null },
        execute = { _: JsonElement -> emptyList<UIMessagePart>() },
    )

    private val allTools = listOf(
        "workspace_read_file", "workspace_write_file", "workspace_edit_file", "workspace_shell",
        "job_start", "job_kill", "job_def_create",
        "search_web", "scrape_web",
        "get_time_info", "clipboard_tool", "eval_javascript", "text_to_speech",
        "memory_tool", "conversation_search", "recent_chats", "use_skill",
        "ask_user",
        "subagent_start", "subagent_kill", "workflow_run", "workflow_create",
        "some_unknown_tool", "mcp__server__tool",
    ).map(::tool)

    private fun names(groups: Set<AgentToolGroup>, excludeJobTools: Boolean = false) =
        AgentToolPolicy.filter(allTools, groups, excludeJobTools).map { it.name }

    @Test
    fun workspaceGroupGivesWorkspaceAndJobs() {
        val got = names(setOf(AgentToolGroup.WORKSPACE))
        assertTrue("workspace_shell" in got)
        assertTrue("job_start" in got)
        assertTrue("web 未声明, 不该给", "search_web" !in got)
        assertTrue("memory_tool" !in got)
    }

    @Test
    fun recursionAndWorkflowAreNeverAllowed() {
        val got = names(AgentToolGroup.entries.toSet())
        assertTrue("递归永远不给: " + got, got.none { it.startsWith("subagent") })
        assertTrue("workflow 永远不给: " + got, got.none { it.startsWith("workflow") })
    }

    @Test
    fun askUserIsNeverAllowed() {
        val got = names(AgentToolGroup.entries.toSet())
        assertTrue("子代理没有 UI 通道, 给了就是死锁", "ask_user" !in got)
    }

    @Test
    fun unknownAndMcpToolsAreExcluded() {
        val got = names(AgentToolGroup.entries.toSet())
        assertTrue("未登记工具默认拒绝", "some_unknown_tool" !in got)
        assertTrue("MCP 工具暂不放开", "mcp__server__tool" !in got)
    }

    @Test
    fun excludeJobToolsRemovesBackgroundJobs() {
        val got = names(setOf(AgentToolGroup.WORKSPACE), excludeJobTools = true)
        assertTrue("应摘掉 job_*: " + got, got.none { it.startsWith("job_") })
        assertTrue("但工作区本身仍可用", "workspace_shell" in got)
    }

    @Test
    fun groupParsingIgnoresUnknownNames() {
        val parsed = AgentToolGroup.parseAll(listOf("workspace", "WEB", "nope", "  local  "))
        assertEquals(
            setOf(AgentToolGroup.WORKSPACE, AgentToolGroup.WEB, AgentToolGroup.LOCAL),
            parsed,
        )
    }
}
