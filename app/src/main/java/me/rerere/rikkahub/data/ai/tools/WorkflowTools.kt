package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
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
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.WorkflowRunDAO
import me.rerere.rikkahub.data.db.entity.WorkflowRunEntity
import me.rerere.rikkahub.data.run.RunStatus
import me.rerere.rikkahub.data.workflow.WorkflowRunRequest
import me.rerere.rikkahub.data.workflow.WorkflowRunner
import me.rerere.rikkahub.data.workflow.WorkflowScriptStore
import me.rerere.rikkahub.data.workflow.WorkflowStartResult

/**
 * workflow 工具的默认审批。
 *
 * 只有 run 需要审批: 那一刻在放行一段**代码**去自主地起一堆子代理 ——
 * 这与 subagent_start 是同一量级的放行, 不能悄悄发生。
 * status / kill 是只读与止损, 不需要审批。
 */
val WorkflowToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workflow_run" to true,
    "workflow_status" to false,
    "workflow_kill" to false,
)

data class WorkflowToolContext(
    val workspaceId: String,
    /**
     * 主会话当前使用的模型 id。
     *
     * workflow 不指定模型时就用它 —— 用户说"用 workflow 做 X"时, 期望的是"用我正在聊的模型",
     * 而不是某个藏在设置里的"快速模型"。
     */
    val defaultModelId: String? = null,
    val conversationId: String?,
    val assistantId: String?,
    val dao: WorkflowRunDAO,
    val runner: WorkflowRunner,
    val scripts: WorkflowScriptStore,
)

private const val DEFAULT_MAX_STEPS = 20
private const val HARD_MAX_STEPS = 60

/** 工具调用里最多等多久（秒）。超过就先返回 runId, 让模型之后用 workflow_status 查。 */
private const val DEFAULT_WAIT_SECONDS = 90
private const val HARD_WAIT_SECONDS = 600

fun createWorkflowTools(
    ctx: WorkflowToolContext,
    needsApproval: (String) -> Boolean,
): List<Tool> = listOf(
    runTool(ctx, needsApproval),
    statusTool(ctx),
    killTool(ctx),
)

private fun schemaString(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun runTool(ctx: WorkflowToolContext, needsApproval: (String) -> Boolean): Tool = Tool(
    name = "workflow_run",
    description = buildString {
        append("Run a dynamic workflow: a JavaScript script that orchestrates subagents itself. ")
        append("Pass an inline script, or the name of one saved under ").append(WorkflowScriptStore.DIR).append(". ")
        append("Inside the script: agent(prompt, { schema, label }) awaits one subagent; ")
        append("pipeline(items, fn) runs one per item; parallel(tasks) runs a set at once; ")
        append("phase(title) and log(message) report progress; args holds the input you pass in. ")
        append("Intermediate results stay in script variables, so only the script\'s return value comes back. ")
        append("Each agent can also set its own reasoning effort: agent(prompt, { reasoning: \"high\" }) — ")
        append("fan out cheap readers at low effort, then synthesize at high effort. ")
        append("Date.now(), Math.random() and new Date() throw on purpose: a rerun must repeat the same agent calls. ")
        append("Returns the result when it finishes within the wait window, otherwise a runId to poll with workflow_status.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("script", schemaString("The workflow script (JavaScript with top-level await). Required unless name is given."))
                put("name", schemaString("Name of a saved workflow to run instead of an inline script"))
                put("title", schemaString("ALWAYS SET THIS: a short title in the user language naming what this run does. It is what the user sees in the run list; without it inline scripts all show up as \"(inline workflow)\"."))
                put("model", schemaString("Model for the subagents, as \"provider/model\" (e.g. \"DeepSeek/deepseek-chat\") or a model name. Omit to use the model of the current conversation, which is usually what the user wants."))
                put("reasoning", schemaString("Reasoning effort for the subagents: off, auto, low, medium, high, xhigh or max. Omit to inherit the current conversation\'s setting. A single agent can override it from the script with agent(prompt, { reasoning: \"high\" })."))
                put("save_as", schemaString("Save this inline script under this name for reuse"))
                put("args", buildJsonObject {
                    put("type", "object")
                    put("description", "Input exposed to the script as the args global (any JSON value)")
                })
                put("cwd", schemaString("Working directory for the subagents, relative to the workspace files root"))
                put("max_steps", buildJsonObject {
                    put("type", "integer")
                    put("description", "Round budget per subagent, default 20")
                })
                put("notify", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Send a system notification when it finishes (default true)")
                })
                put("auto_wake", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Wake this conversation with the result when it finishes (default false). Costs another generation, so only use it when the user asked to be told.")
                })
                put("wait_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "How long to wait for the result before returning a runId, default 90")
                })
            },
            required = emptyList(),
        )
    },
    needsApproval = { needsApproval("workflow_run") },
    execute = { element -> executeRun(ctx, element.jsonObject) },
)

private suspend fun executeRun(
    ctx: WorkflowToolContext,
    params: JsonObject,
): List<UIMessagePart> = runToolSafely {
    val script = params.strJson("script")?.takeIf { it.isNotBlank() }
    val name = params.strJson("name")?.takeIf { it.isNotBlank() }
    if (script == null && name == null) {
        return@runToolSafely jsonError("invalid_params", "either script or name is required")
    }
    val maxSteps = (params.intJson("max_steps") ?: DEFAULT_MAX_STEPS).coerceIn(1, HARD_MAX_STEPS)
    val waitSeconds = (params.intJson("wait_seconds") ?: DEFAULT_WAIT_SECONDS)
        .coerceIn(0, HARD_WAIT_SECONDS)
    val args = params["args"] as? JsonObject ?: JsonObject(emptyMap())

    val started = ctx.runner.start(
        WorkflowRunRequest(
            workspaceId = ctx.workspaceId,
            // 标题与模型直接交给 runner: 模型解析（provider/model / 显示名 / UUID）与
            // "解析不到就报错并列出候选"的逻辑在那里, 工具层不做第二套。
            title = params.strJson("title")?.takeIf { it.isNotBlank() },
            modelSpec = params.strJson("model")?.takeIf { it.isNotBlank() },
            reasoningSpec = params.strJson("reasoning")?.takeIf { it.isNotBlank() },
            notify = params.boolJson("notify") ?: true,
            autoWake = params.boolJson("auto_wake") ?: false,
            defaultModelId = ctx.defaultModelId,
            conversationId = ctx.conversationId,
            assistantId = ctx.assistantId,
            source = script,
            name = name,
            saveAs = params.strJson("save_as")?.takeIf { it.isNotBlank() },
            args = args,
            cwd = params.strJson("cwd")?.takeIf { it.isNotBlank() },
            maxSteps = maxSteps,
        )
    )
    when (started) {
        is WorkflowStartResult.Rejected ->
            jsonError("workflow_not_started", started.error)

        is WorkflowStartResult.Started -> {
            // 等一小段: 大多数 workflow 在这段时间里就好了, 一次工具调用直接把结果带回去,
            // 模型不用"起一次再查一次"。等不完就先给它 runId —— 绝不能把会话挂在那里死等。
            val finished = waitSeconds > 0 &&
                ctx.runner.await(started.runId, waitSeconds * 1000L)
            val row = ctx.dao.getById(started.runId)
            jsonText(
                buildJsonObject {
                    put("runId", started.runId)
                    put("name", started.name)
                    put("finished", finished)
                    if (started.scriptPath != null) put("scriptPath", started.scriptPath)
                    // JsonObjectBuilder 没有 putAll: 逐个搬过去
                    row?.toJson(includeResult = true)?.forEach { (key, value) -> put(key, value) }
                }
            )
        }
    }
}

private fun statusTool(ctx: WorkflowToolContext): Tool = Tool(
    name = "workflow_status",
    description = buildString {
        append("Check one workflow run, or list recent runs and saved workflows. ")
        append("Reports the current phase, the agent counts (how many ran, how many were reused from an earlier run) ")
        append("and the result once it finished.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("run_id", schemaString("Omit to list recent runs instead"))
                put("limit", buildJsonObject { put("type", "integer") })
                put("tail", buildJsonObject {
                    put("type", "integer")
                    put("description", "How many progress entries to include from the end, default 30")
                })
            },
            required = emptyList(),
        )
    },
    needsApproval = { false },
    execute = { element -> executeStatus(ctx, element.jsonObject) },
)

private suspend fun executeStatus(
    ctx: WorkflowToolContext,
    params: JsonObject,
): List<UIMessagePart> = runToolSafely {
    val runId = params.strJson("run_id")?.takeIf { it.isNotBlank() }
    if (runId != null) {
        val row = ctx.dao.getById(runId)
            ?: return@runToolSafely jsonError("run_not_found", "No such workflow run: " + runId)
        val tail = (params.intJson("tail") ?: 30).coerceIn(0, 200)
        return@runToolSafely jsonText(
            buildJsonObject {
                row.toJson(includeResult = true).forEach { (key, value) -> put(key, value) }
                put("running", ctx.runner.isRunning(runId))
                put("progressTail", progressTail(row.progressJson, tail))
            }
        )
    }

    val limit = (params.intJson("limit") ?: 10).coerceIn(1, 50)
    val runs = ctx.dao.listRecent(limit)
    // 把"有哪些已保存的脚本"一并报出来: 模型下一次就能直接按名复用, 不必再问
    val saved = runCatching { ctx.scripts.list(ctx.workspaceId) }.getOrDefault(emptyList())
    jsonText(
        buildJsonObject {
            put("savedWorkflows", buildJsonArray { saved.forEach { add(JsonPrimitive(it)) } })
            put("savedWorkflowsDir", WorkflowScriptStore.DIR)
            put("runs", buildJsonArray { runs.forEach { add(it.toJson(includeResult = false)) } })
        }
    )
}

private fun killTool(ctx: WorkflowToolContext): Tool = Tool(
    name = "workflow_kill",
    description = "Stop a running workflow. Its in-flight subagents are cancelled with it.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { put("run_id", schemaString("The workflow run id")) },
            required = listOf("run_id"),
        )
    },
    needsApproval = { false },
    execute = { element -> executeKill(ctx, element.jsonObject) },
)

private suspend fun executeKill(
    ctx: WorkflowToolContext,
    params: JsonObject,
): List<UIMessagePart> = runToolSafely {
    val runId = params.strJson("run_id")?.takeIf { it.isNotBlank() }
        ?: return@runToolSafely jsonError("invalid_params", "run_id is required")
    val stopped = ctx.runner.kill(runId)
    val after = ctx.dao.getById(runId)
    jsonText(
        buildJsonObject {
            put("runId", runId)
            put("requested", stopped)
            put("status", after?.status?.let { JsonPrimitive(it) } ?: JsonNull)
        }
    )
}

/**
 * 运行的对外投影。
 *
 * 列表视图**不带** result: 一个 workflow 的返回可能很长, 十条一起塞进上下文是浪费。
 */
private fun WorkflowRunEntity.toJson(includeResult: Boolean): JsonObject = buildJsonObject {
    put("runId", id)
    put("name", name)
    put("model", modelName?.let { JsonPrimitive(it) } ?: JsonNull)
    put("status", status)
    put("finished", RunStatus.from(status).isFinished)
    put("phase", phase?.let { JsonPrimitive(it) } ?: JsonNull)
    put("agentsCalled", agentsCalled)
    put("agentsReused", agentsReused)
    put("error", error?.let { JsonPrimitive(it) } ?: JsonNull)
    if (includeResult) put("result", resultJson?.let { JsonPrimitive(it) } ?: JsonNull)
    put("createdAt", createdAt)
    put("startedAt", startedAt?.let { JsonPrimitive(it) } ?: JsonNull)
    put("finishedAt", finishedAt?.let { JsonPrimitive(it) } ?: JsonNull)
    put("runtimeMs", runtimeMs?.let { JsonPrimitive(it) } ?: JsonNull)
}

private fun progressTail(progressJson: String, tail: Int): JsonArray {
    if (tail <= 0) return JsonArray(emptyList())
    val all = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(progressJson) as? JsonArray
    }.getOrNull() ?: return JsonArray(emptyList())
    return JsonArray(all.takeLast(tail))
}
