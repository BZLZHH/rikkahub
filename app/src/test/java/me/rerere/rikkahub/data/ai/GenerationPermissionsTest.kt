package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.permission.PermissionContext
import me.rerere.rikkahub.data.permission.PermissionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationPermissionsTest {
    private fun message(vararg tools: UIMessagePart.Tool) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = tools.toList(),
    )

    private fun tool(name: String, id: String = name) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = """{"command":"ls","path":"/workspace/test.txt"}""",
    )

    private fun interactive(mode: PermissionMode) = PermissionContext.interactive(mode, emptyList())

    @Test
    fun asksForConsentAndPreservesOtherMetadata() {
        val original = tool("workspace_shell").copy(metadata = buildJsonObject { put("source", "test") })
        val result = message(original).withToolPermissions(interactive(PermissionMode.ASK)).getTools().single()
        assertEquals(ToolApprovalState.Pending, result.approvalState)
        assertTrue(result.metadata!!.containsKey("consent"))
        assertEquals(original.metadata!!["source"], result.metadata!!["source"])
    }

    @Test
    fun decisionsSurviveResumeButNewCallsAreChecked() {
        val approved = tool("workspace_shell", "approved").copy(approvalState = ToolApprovalState.Approved)
        val answered = tool("ask_user").copy(approvalState = ToolApprovalState.Answered("yes"))
        val completed = tool("workspace_write_file").copy(output = listOf(UIMessagePart.Text("done")))
        val fresh = tool("workspace_shell", "new")
        val result = message(approved, answered, completed, fresh)
            .withToolPermissions(interactive(PermissionMode.PLAN)).getTools()
        assertEquals(approved, result[0])
        assertEquals(answered, result[1])
        assertEquals(completed, result[2])
        assertTrue(result[3].approvalState is ToolApprovalState.Denied)
    }

    @Test
    fun humanInteractionStillWaitsEvenInFullMode() {
        val result = message(tool("ask_user")).withToolPermissions(interactive(PermissionMode.FULL))
        assertEquals(ToolApprovalState.Pending, result.getTools().single().approvalState)
    }

    @Test
    fun backgroundAllowsWorkspaceButRejectsHumanAndDelegationWithoutWaiting() {
        val result = message(tool("workspace_shell"), tool("ask_user"), tool("subagent_start"))
            .withToolPermissions(PermissionContext.background()).getTools()
        assertEquals(ToolApprovalState.Approved, result[0].approvalState)
        assertTrue(result[1].approvalState is ToolApprovalState.Denied)
        assertTrue(result[2].approvalState is ToolApprovalState.Denied)
        assertTrue(result.none { it.isPending })
    }
}
