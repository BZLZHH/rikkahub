package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.db.dao.WorkflowDAO
import me.rerere.rikkahub.data.db.entity.WorkflowEntity
import me.rerere.rikkahub.data.db.entity.WorkflowRunEntity
import me.rerere.rikkahub.data.run.RunStatus
import me.rerere.rikkahub.data.workflow.StepOnError
import me.rerere.rikkahub.data.workflow.WorkflowExport
import me.rerere.rikkahub.data.workflow.WorkflowParam
import me.rerere.rikkahub.data.workflow.WorkflowRunEngine
import me.rerere.rikkahub.data.workflow.WorkflowStartResult
import me.rerere.rikkahub.data.workflow.WorkflowStep
import me.rerere.rikkahub.data.workflow.WorkflowStepState
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/** 工作流工具的默认审批: 建定义与起运行要审批, 查询与停止不要。 */
val WorkflowToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workflow_create" to true,
    "workflow_run" to true,
    "workflow_status" to false,
    "workflow_kill" to false,
)

data class WorkflowToolContext(
    val workspaceId: String,
    val conversationId: String?,
    val assistantId: String?,
    val dao: WorkflowDAO,
    val engine: WorkflowRunEngine,
)

fun createWorkflowTools(
    ctx: WorkflowToolContext,
    needsApproval: (String) -> Boolean,
): List<Tool> = listOf(
    workflowCreateTool(ctx, needsApproval),
    workflowRunTool(ctx, needsApproval),
    workflowStatusTool(ctx),
    workflowKillTool(ctx),
)

private fun workflowCreateTool(ctx: WorkflowToolContext, needsApproval: (String) -> Boolean) = Tool(
    name = "workflow_create",
    description = buildString {
        append("Create a reusable multi-step workflow in this workspace. ")
        append("Steps run in order; each step is a shell command executed as its own background job. ")
        append("Steps pass data through files: the engine injects RIKKA_OUT for each step and exposes ")
        append("the keys named in export.keys to later steps as {{steps.<id>.<key>}}; the exit code is ")
        append("always available as {{steps.<id>.exit_code}}. Workflow params use {{name}}; add a |raw ")
        append("suffix ({{name|raw}}) to insert a value without shell quoting. ")
        append("on_error=fail stops the run (later steps become skipped); retries=N retries that step.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", schemaString("Short stable identifier, unique per workspace"))
                put("description", schemaString("REQUIRED: one sentence in the user language describing what this workflow does"))
                put("steps", buildJsonObject {
                    put("type", "array")
                    put("description", "Array of {id, command, cwd?, mode?, params?, env?, timeout_seconds?, on_error?, retries?, export?}")
                })
                put("params", buildJsonObject {
                    put("type", "array")
                    put("description", "Array of {name, required, default, description}")
                })
                put("env", buildJsonObject { put("type", "object") })
                put("notify", buildJsonObject { put("type", "boolean") })
                put("auto_wake", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("name", "steps"),
        )
    },
    needsApproval = { needsApproval("workflow_create") },
    execute = { element -> executeCreate(ctx, element.jsonObject) },
)

private suspend fun executeCreate(
    ctx: WorkflowToolContext,
    params: JsonObject,
): List<me.rerere.ai.ui.UIMessagePart> = runToolSafely {
    val name = params.strJson("name")?.trim()
    if (name.isNullOrBlank()) return@runToolSafely jsonError("invalid_params", "name is required")
    if (ctx.dao.getWorkflowByName(ctx.workspaceId, name) != null) {
        return@runToolSafely jsonError("duplicate_name", "A workflow named " + name + " already exists")
    }
    val rawSteps = params["steps"] as? JsonArray
    if (rawSteps == null || rawSteps.isEmpty()) {
        return@runToolSafely jsonError("invalid_params", "steps must be a non-empty array")
    }
    val steps = mutableListOf<WorkflowStep>()
    for (element in rawSteps) {
        val obj = element as? JsonObject ?: continue
        val id = obj.strJson("id")?.trim()
        val command = obj.strJson("command")
        if (id.isNullOrBlank() || command.isNullOrBlank()) {
            return@runToolSafely jsonError("invalid_params", "every step needs id and command")
        }
        val exportKeys = (obj["export"] as? JsonObject)?.get("keys") as? JsonArray
        steps += WorkflowStep(
            id = id,
            command = command,
            cwd = obj.strJson("cwd") ?: "",
            mode = obj.strJson("mode") ?: "PIPE",
            timeoutSeconds = obj.intJson("timeout_seconds")?.toLong(),
            onError = if (obj.strJson("on_error")?.equals("continue", true) == true) {
                StepOnError.CONTINUE
            } else {
                StepOnError.FAIL
            },
            retries = (obj.intJson("retries") ?: 0).coerceIn(0, 5),
            export = exportKeys?.let { keys ->
                WorkflowExport(keys = keys.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
            },
        )
    }
    val ids = steps.map { it.id }
    if (ids.size != ids.distinct().size) {
        return@runToolSafely jsonError("invalid_params", "step ids must be unique")
    }
    val declaredParams = (params["params"] as? JsonArray)?.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val paramName = obj.strJson("name") ?: return@mapNotNull null
        WorkflowParam(
            name = paramName,
            required = obj.boolJson("required") ?: false,
            default = obj.strJson("default"),
            description = obj.strJson("description"),
        )
    }.orEmpty()

    val now = System.currentTimeMillis()
    val workflow = WorkflowEntity(
        id = Uuid.random().toString(),
        workspaceId = ctx.workspaceId,
        name = name,
        description = params.strJson("description")?.takeIf { it.isNotBlank() },
        paramsJson = runCatching { JsonInstant.encodeToString(declaredParams) }.getOrDefault("[]"),
        stepsJson = runCatching { JsonInstant.encodeToString(steps) }.getOrDefault("[]"),
        envJson = runCatching {
            JsonInstant.encodeToString(params.stringMapJson("env"))
        }.getOrDefault("{}"),
        notify = params.boolJson("notify") ?: true,
        autoWake = params.boolJson("auto_wake") ?: false,
        createdAt = now,
        updatedAt = now,
    )
    ctx.dao.upsertWorkflow(workflow)
    jsonText(buildJsonObject {
        put("workflowId", workflow.id)
        put("name", workflow.name)
        put("steps", buildJsonArray { steps.forEach { add(JsonPrimitive(it.id)) } })
    })
}

private fun workflowRunTool(ctx: WorkflowToolContext, needsApproval: (String) -> Boolean) = Tool(
    name = "workflow_run",
    description = "Run a saved workflow. Returns a run id; follow up with workflow_status / workflow_kill.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("workflow", schemaString("Workflow id or name"))
                put("args", buildJsonObject {
                    put("type", "object")
                    put("description", "Values for the workflow params")
                })
            },
            required = listOf("workflow"),
        )
    },
    needsApproval = { needsApproval("workflow_run") },
    execute = { element -> executeRun(ctx, element.jsonObject) },
)

private suspend fun executeRun(
    ctx: WorkflowToolContext,
    params: JsonObject,
): List<me.rerere.ai.ui.UIMessagePart> = runToolSafely {
    val reference = params.strJson("workflow")?.trim()
    if (reference.isNullOrBlank()) {
        return@runToolSafely jsonError("invalid_params", "workflow is required")
    }
    val workflow = findWorkflow(ctx, reference)
        ?: return@runToolSafely jsonError("workflow_not_found", "No workflow named " + reference)
    val result = ctx.engine.start(
        workflow = workflow,
        args = params.stringMapJson("args"),
        conversationId = ctx.conversationId,
        assistantId = ctx.assistantId,
        triggerSource = "AI",
    )
    when (result) {
        is WorkflowStartResult.Rejected -> jsonError("workflow_not_started", result.error)
        is WorkflowStartResult.Started -> jsonText(buildJsonObject {
            put("runId", result.runId)
            put("workflowId", workflow.id)
            put("status", RunStatus.RUNNING.name)
        })
    }
}

private fun workflowStatusTool(ctx: WorkflowToolContext) = Tool(
    name = "workflow_status",
    description = "Check a workflow run: overall status plus per-step status, exit code and outputs.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("run_id", schemaString("Run id from workflow_run"))
            },
            required = listOf("run_id"),
        )
    },
    needsApproval = { false },
    execute = { element -> executeStatus(ctx, element.jsonObject) },
)

private suspend fun executeStatus(
    ctx: WorkflowToolContext,
    params: JsonObject,
): List<me.rerere.ai.ui.UIMessagePart> = runToolSafely {
    val runId = params.strJson("run_id")
    if (runId.isNullOrBlank()) {
        return@runToolSafely jsonError("invalid_params", "run_id is required")
    }
    val run = ctx.dao.getRun(runId)
        ?: return@runToolSafely jsonError("run_not_found", "No such workflow run: " + runId)
    jsonText(run.toJson(ctx.engine.isRunning(runId)))
}

private fun workflowKillTool(ctx: WorkflowToolContext) = Tool(
    name = "workflow_kill",
    description = "Stop a running workflow. The current step is stopped too.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("run_id", schemaString("Run id from workflow_run"))
            },
            required = listOf("run_id"),
        )
    },
    needsApproval = { false },
    execute = { element -> executeKill(ctx, element.jsonObject) },
)

private suspend fun executeKill(
    ctx: WorkflowToolContext,
    params: JsonObject,
): List<me.rerere.ai.ui.UIMessagePart> = runToolSafely {
    val runId = params.strJson("run_id")
    if (runId.isNullOrBlank()) {
        return@runToolSafely jsonError("invalid_params", "run_id is required")
    }
    if (ctx.dao.getRun(runId) == null) {
        return@runToolSafely jsonError("run_not_found", "No such workflow run: " + runId)
    }
    val stopped = ctx.engine.kill(runId)
    val after = ctx.dao.getRun(runId)
    jsonText(buildJsonObject {
        put("runId", runId)
        put("requested", stopped)
        put("status", after?.status ?: RunStatus.PENDING.name)
    })
}

private suspend fun findWorkflow(ctx: WorkflowToolContext, reference: String): WorkflowEntity? =
    ctx.dao.getWorkflow(reference) ?: ctx.dao.getWorkflowByName(ctx.workspaceId, reference)

private fun WorkflowRunEntity.toJson(live: Boolean): JsonObject = buildJsonObject {
    put("runId", id)
    put("workflowId", workflowId)
    put("title", title)
    put("status", status)
    put("live", live)
    put("currentStep", currentStep?.let { JsonPrimitive(it) } ?: JsonNull)
    put("error", error?.let { JsonPrimitive(it) } ?: JsonNull)
    put("startedAt", startedAt?.let { JsonPrimitive(it) } ?: JsonNull)
    put("finishedAt", finishedAt?.let { JsonPrimitive(it) } ?: JsonNull)
    put("steps", buildJsonArray {
        runCatching { JsonInstant.decodeFromString<List<WorkflowStepState>>(stepStatesJson) }
            .getOrDefault(emptyList())
            .forEach { state ->
                add(buildJsonObject {
                    put("id", state.id)
                    put("status", state.status.name)
                    put("jobId", state.jobId?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("exitCode", state.exitCode?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("attempts", state.attempts)
                    put("error", state.error?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
    })
}

private fun JsonObject.stringMapJson(name: String): Map<String, String> {
    val obj = this[name] as? JsonObject ?: return emptyMap()
    return obj.entries.mapNotNull { (key, value) ->
        val text = (value as? JsonPrimitive)?.contentOrNull
        if (text == null) null else key to text
    }.toMap()
}

private fun schemaString(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}
