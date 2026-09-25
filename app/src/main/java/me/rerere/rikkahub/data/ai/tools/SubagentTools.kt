package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.agent.AgentToolGroup
import me.rerere.rikkahub.data.agent.AgentTranscriptStore
import me.rerere.rikkahub.data.db.dao.AgentRunDAO
import me.rerere.rikkahub.data.db.entity.AgentRunEntity
import me.rerere.rikkahub.data.run.RunKind
import me.rerere.rikkahub.data.run.RunLaunchResult
import me.rerere.rikkahub.data.run.RunOrchestrator
import me.rerere.rikkahub.data.run.RunQuota
import me.rerere.rikkahub.data.run.RunRecord
import me.rerere.rikkahub.data.run.RunStatus
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/** 子代理工具的默认审批（只有 start 需要: 那一刻在放行一个会自主行动的代理）。 */
val SubagentToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "subagent_start" to true,
    "subagent_status" to false,
    "subagent_result" to false,
    "subagent_kill" to false,
)

data class SubagentToolContext(
    val workspaceId: String,
    val conversationId: String?,
    val assistantId: String?,
    val dao: AgentRunDAO,
    val transcripts: AgentTranscriptStore,
    val orchestrator: RunOrchestrator,
)

private const val DEFAULT_MAX_STEPS = 20
private const val HARD_MAX_STEPS = 60

fun createSubagentTools(
    ctx: SubagentToolContext,
    needsApproval: (String) -> Boolean,
): List<Tool> {
    return listOf(
        startTool(ctx, needsApproval),
        statusTool(ctx),
        resultTool(ctx),
        killTool(ctx),
    )
}

private fun schemaString(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun startTool(ctx: SubagentToolContext, needsApproval: (String) -> Boolean): Tool {
    return Tool(
        name = "subagent_start",
        description = buildString {
            append("Delegate a self-contained task to a subagent and return immediately. ")
            append("The subagent runs in its own context window with a restricted tool set, ")
            append("so it cannot see this conversation: put everything it needs into the prompt. ")
            append("Follow up with subagent_status, read output via subagent_result, stop with subagent_kill.")
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("title", schemaString("REQUIRED: one short sentence in the user language telling WHAT this subagent does. Shown in the UI."))
                    put("prompt", schemaString("The complete task. The subagent cannot see this conversation."))
                    put("tool_groups", buildJsonObject {
                        put("type", "array")
                        put("description", "Extra capabilities: workspace (default), web, local, memory, conversation, skill. Recursion, workflow and ask_user are NEVER granted.")
                    })
                    put("exclude_job_tools", buildJsonObject {
                        put("type", "boolean")
                        put("description", "When true the subagent can use the workspace but cannot start background jobs")
                    })
                    put("model_id", schemaString("Model id to use; omit for the fast model"))
                    put("cwd", schemaString("Working directory relative to the workspace files root"))
                    put("max_steps", buildJsonObject {
                        put("type", "integer")
                        put("description", "Round budget (tool-call rounds), default 20")
                    })
                    put("notify", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Send a notification when it finishes (default true)")
                    })
                    put("auto_wake", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Wake this conversation with the result (default false)")
                    })
                },
                required = listOf("title", "prompt"),
            )
        },
        needsApproval = { needsApproval("subagent_start") },
        execute = { element -> executeStart(ctx, element.jsonObject) },
    )
}

private suspend fun executeStart(
    ctx: SubagentToolContext,
    params: JsonObject,
): List<me.rerere.ai.ui.UIMessagePart> = runToolSafely {
    val title = params.strJson("title")?.trim()
    val prompt = params.strJson("prompt")?.trim()
    if (title.isNullOrBlank() || prompt.isNullOrBlank()) {
        return@runToolSafely jsonError("invalid_params", "title and prompt are required")
    }

    val rawGroups = params.stringListJson("tool_groups")
    val unknown = rawGroups.filter { AgentToolGroup.from(it) == null }
    if (unknown.isNotEmpty()) {
        val allowed = AgentToolGroup.entries.joinToString(", ") { it.name.lowercase() }
        return@runToolSafely jsonError(
            "invalid_params",
            "Unknown tool group(s): " + unknown.joinToString(", ") + ". Allowed: " + allowed,
        )
    }
    val groups = AgentToolGroup.parseAll(rawGroups).ifEmpty { setOf(AgentToolGroup.WORKSPACE) }
    val excludeJobTools = params.boolJson("exclude_job_tools") ?: false
    val maxSteps = (params.intJson("max_steps") ?: DEFAULT_MAX_STEPS).coerceIn(1, HARD_MAX_STEPS)
    val notify = params.boolJson("notify") ?: true
    val autoWake = params.boolJson("auto_wake") ?: false

    val runId = Uuid.random().toString()
    val args = buildJsonObject {
        params.strJson("cwd")?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
        put("max_steps", maxSteps)
        put("exclude_job_tools", excludeJobTools.toString())
    }
    val run = AgentRunEntity(
        id = runId,
        workspaceId = ctx.workspaceId,
        conversationId = ctx.conversationId,
        assistantId = ctx.assistantId,
        title = title,
        prompt = prompt,
        modelId = params.strJson("model_id"),
        toolGroupsJson = JsonInstant.encodeToString(groups.map { it.name }),
        argsJson = args.toString(),
        status = RunStatus.PENDING.name,
        triggerSource = "AI",
        createdAt = System.currentTimeMillis(),
        notify = notify,
        autoWake = autoWake,
    )
    ctx.dao.upsert(run)

    val record = RunRecord(
        runId = runId,
        kind = RunKind.AGENT,
        workspaceId = ctx.workspaceId,
        title = title,
        status = RunStatus.PENDING,
        maxRuntimeMs = run.maxRuntimeMs,
        conversationId = ctx.conversationId,
        assistantId = ctx.assistantId,
        notify = notify,
        autoWake = autoWake,
        triggerSource = "AI",
    )
    val started = ctx.orchestrator.start(record)
    if (started is RunLaunchResult.NotStarted) {
        return@runToolSafely jsonError("subagent_not_started", started.error)
    }

    jsonText(buildJsonObject {
        put("runId", runId)
        put("status", RunStatus.RUNNING.name)
        put("toolGroups", groups.joinToString(", ") { it.name.lowercase() })
        put("maxSteps", maxSteps)
    })
}

private fun statusTool(ctx: SubagentToolContext): Tool {
    return Tool(
        name = "subagent_status",
        description = "Check one subagent, or list recent ones. Reports rounds done, tool calls and token usage.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("run_id", schemaString("Omit to list recent subagents instead"))
                    put("limit", buildJsonObject { put("type", "integer") })
                },
                required = emptyList(),
            )
        },
        needsApproval = { false },
        execute = { element -> executeStatus(ctx, element.jsonObject) },
    )
}

private suspend fun executeStatus(
    ctx: SubagentToolContext,
    params: JsonObject,
): List<me.rerere.ai.ui.UIMessagePart> = runToolSafely {
    val runId = params.strJson("run_id")
    if (!runId.isNullOrBlank()) {
        val run = ctx.dao.getById(runId)
        if (run == null) {
            return@runToolSafely jsonError("run_not_found", "No such subagent run: " + runId)
        }
        jsonText(run.toJson(ctx.orchestrator.isRunning(runId)))
    } else {
        val limit = (params.intJson("limit") ?: 10).coerceIn(1, 50)
        val runs = ctx.dao.listRecent(limit)
        jsonText(buildJsonObject {
            put("running", ctx.orchestrator.countOf(RunKind.AGENT))
            put("limit", RunQuota.DEFAULT_MAX_CONCURRENT_AGENTS)
            put("runs", buildJsonArray { runs.forEach { add(it.toJson(ctx.orchestrator.isRunning(it.id))) } })
        })
    }
}

private fun resultTool(ctx: SubagentToolContext): Tool {
    return Tool(
        name = "subagent_result",
        description = "Read a finished subagent result and the tail of its transcript.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("run_id", schemaString("The subagent run id"))
                    put("tail_lines", buildJsonObject {
                        put("type", "integer")
                        put("description", "How many transcript lines to return from the end (default 40)")
                    })
                },
                required = listOf("run_id"),
            )
        },
        needsApproval = { false },
        execute = { element -> executeResult(ctx, element.jsonObject) },
    )
}

private suspend fun executeResult(
    ctx: SubagentToolContext,
    params: JsonObject,
): List<me.rerere.ai.ui.UIMessagePart> = runToolSafely {
    val runId = params.strJson("run_id")
    if (runId.isNullOrBlank()) {
        return@runToolSafely jsonError("invalid_params", "run_id is required")
    }
    val run = ctx.dao.getById(runId)
    if (run == null) {
        return@runToolSafely jsonError("run_not_found", "No such subagent run: " + runId)
    }
    val tailLines = (params.intJson("tail_lines") ?: 40).coerceIn(1, 500)
    val total = ctx.transcripts.lineCount(runId)
    val from = (total - tailLines).coerceAtLeast(0)
    val tail = ctx.transcripts.read(runId, from, tailLines)
    jsonText(buildJsonObject {
        put("runId", runId)
        put("status", run.status)
        put("error", run.error?.let { JsonPrimitive(it) } ?: JsonNull)
        put("result", run.resultJson?.let { JsonPrimitive(it) } ?: JsonNull)
        put("transcriptLines", total)
        put("transcriptTail", buildJsonArray { tail.forEach { add(JsonPrimitive(it)) } })
    })
}

private fun killTool(ctx: SubagentToolContext): Tool {
    return Tool(
        name = "subagent_kill",
        description = "Stop a running subagent. Its in-flight model request is cancelled.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject { put("run_id", schemaString("The subagent run id")) },
                required = listOf("run_id"),
            )
        },
        needsApproval = { false },
        execute = { element -> executeKill(ctx, element.jsonObject) },
    )
}

private suspend fun executeKill(
    ctx: SubagentToolContext,
    params: JsonObject,
): List<me.rerere.ai.ui.UIMessagePart> = runToolSafely {
    val runId = params.strJson("run_id")
    if (runId.isNullOrBlank()) {
        return@runToolSafely jsonError("invalid_params", "run_id is required")
    }
    val run = ctx.dao.getById(runId)
    if (run == null) {
        return@runToolSafely jsonError("run_not_found", "No such subagent run: " + runId)
    }
    val stopped = ctx.orchestrator.kill(runId)
    val after = ctx.dao.getById(runId)
    jsonText(buildJsonObject {
        put("runId", runId)
        put("requested", stopped)
        put("status", after?.status ?: run.status)
    })
}

private fun AgentRunEntity.toJson(live: Boolean): JsonObject = buildJsonObject {
    put("runId", id)
    put("title", title)
    put("status", status)
    put("live", live)
    put("stepsDone", stepsDone)
    put("toolCalls", toolCalls)
    put("tokensIn", tokensIn)
    put("tokensOut", tokensOut)
    put("error", error?.let { JsonPrimitive(it) } ?: JsonNull)
    put("startedAt", startedAt?.let { JsonPrimitive(it) } ?: JsonNull)
    put("finishedAt", finishedAt?.let { JsonPrimitive(it) } ?: JsonNull)
}
