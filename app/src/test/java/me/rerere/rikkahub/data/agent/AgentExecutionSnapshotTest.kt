package me.rerere.rikkahub.data.agent

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class AgentExecutionSnapshotTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val workspace = Uuid.random()
    private val model = Model(modelId = "parent")
    private val profile = Assistant(
        chatModelId = model.id,
        reasoningLevel = ReasoningLevel.OFF,
        enableWebSearch = false,
        workspaceId = Uuid.random(),
    )

    private fun captured(): AgentExecutionSnapshot {
        val settings = Settings(
            assistantId = profile.id,
            assistants = listOf(profile),
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
        )
        // This is the live conversation configuration; no persisted conversation is consulted.
        val live = Conversation(
            assistantId = profile.id,
            messageNodes = emptyList(),
            config = ConversationConfig(
                chatModelId = model.id,
                reasoningLevel = ReasoningLevel.HIGH,
                enableWebSearch = true,
                builtInSearch = true,
                workspaceId = workspace,
                enabledSkills = setOf("review"),
            ),
            workspaceCwd = "src",
        )
        return AgentExecutionSnapshot(
            settings.getAssistantOf(live),
            checkNotNull(settings.getChatModelOf(live)),
            live.workspaceCwd,
        )
    }

    @Test
    fun liveConversationOverridesSurviveAgentArgumentsRoundTrip() {
        val restored = checkNotNull(AgentExecutionSnapshot.fromJson(captured().toJson()))
        val assistant = restored.assistantFor(workspace.toString())
        assertEquals(ReasoningLevel.HIGH, assistant.reasoningLevel)
        assertTrue(assistant.enableWebSearch)
        assertEquals(setOf("review"), assistant.enabledSkills)
        assertEquals(workspace, assistant.workspaceId)
        assertEquals(profile.localTools, assistant.localTools)
        assertTrue(BuiltInTools.Search in restored.model.tools)
        assertEquals("src", restored.workspaceCwd)
    }

    @Test
    fun perAgentReasoningOverridesCapturedConversationReasoning() {
        val snapshot = captured()
        assertEquals(ReasoningLevel.HIGH, snapshot.assistantFor(workspace.toString()).reasoningLevel)
        assertEquals(ReasoningLevel.LOW, snapshot.assistantFor(workspace.toString(), "low").reasoningLevel)
        assertEquals(ReasoningLevel.HIGH, snapshot.assistantFor(workspace.toString(), "invalid").reasoningLevel)
    }

    @Test
    fun explicitModelWinsWhileDefaultFastModelKeepsSearchToggle() {
        val snapshot = captured()
        val fast = Model(modelId = "fast")
        assertTrue(BuiltInTools.Search in snapshot.modelFor(fast, explicitOverride = false).tools)
        assertEquals(fast, snapshot.modelFor(fast, explicitOverride = true))
        val disabled = snapshot.copy(model = snapshot.model.copy(tools = emptySet()))
        assertFalse(BuiltInTools.Search in disabled.modelFor(fast.copy(tools = setOf(BuiltInTools.Search)), false).tools)
    }

    @Test
    fun workflowRetryReadsPrivateSnapshotAfterStoreRecreation() {
        val directory = temporary.newFolder()
        val runId = Uuid.random().toString()
        val original = captured()
        AgentTranscriptStore(directory).writeExecutionSnapshot(runId, original)
        val reopened = AgentTranscriptStore(directory)
        assertEquals(original, reopened.readExecutionSnapshot(runId))
        assertEquals(emptyList<String>(), reopened.read(runId, 0))
        reopened.delete(runId)
        assertNull(reopened.readExecutionSnapshot(runId))
    }
}
