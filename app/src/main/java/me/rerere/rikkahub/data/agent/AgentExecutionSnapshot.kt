package me.rerere.rikkahub.data.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/** Captured from the live conversation when its tools are built, never reloaded from a database snapshot. */
@Serializable
data class AgentExecutionSnapshot(
    val assistant: Assistant,
    val model: Model,
    val workspaceCwd: String? = null,
) {
    fun assistantFor(workspaceId: String, reasoning: String? = null): Assistant = assistant.copy(
        workspaceId = Uuid.parse(workspaceId),
        reasoningLevel = reasoning?.let(ReasoningLevel::fromSpec) ?: assistant.reasoningLevel,
    )

    /** Explicit model selection wins; the default fast model keeps the conversation search switch. */
    fun modelFor(selected: Model, explicitOverride: Boolean): Model = when {
        explicitOverride -> selected
        selected.id == model.id -> model
        else -> selected.copy(
            tools = if (BuiltInTools.Search in model.tools) selected.tools + BuiltInTools.Search
            else selected.tools - BuiltInTools.Search,
        )
    }

    fun toJson(): JsonElement = JsonInstant.encodeToJsonElement(serializer(), this)

    companion object {
        const val ARG_KEY = "execution_snapshot"

        fun fromJson(value: JsonElement?): AgentExecutionSnapshot? = value?.let {
            JsonInstant.decodeFromJsonElement(serializer(), it)
        }
    }
}
