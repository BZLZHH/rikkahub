package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.WorkspaceJobDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceJobDefEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceJobEntity
import me.rerere.rikkahub.data.job.JobLogChunk
import me.rerere.rikkahub.data.job.JobLogStream
import me.rerere.rikkahub.data.job.JobParamDto
import me.rerere.rikkahub.data.job.JobScheduleEngine
import me.rerere.rikkahub.data.job.JobTrigger
import me.rerere.rikkahub.data.job.JobTriggerSource
import me.rerere.rikkahub.data.job.WorkspaceJobManager
import me.rerere.rikkahub.data.job.WorkspaceJobMode
import me.rerere.rikkahub.data.job.WorkspaceJobStatus
import me.rerere.rikkahub.data.job.encodeTrigger
import me.rerere.rikkahub.data.job.params
import me.rerere.rikkahub.data.job.trigger
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.CronExpression
import kotlin.uuid.Uuid

private const val DEFAULT_WAIT_SECONDS = 60L
private const val MAX_WAIT_SECONDS = 600L

/** job_* 工具需要的运行期上下文（按"绑定 workspace 的助手"注入）。 */
data class JobToolContext(
    val workspaceId: String,
    val conversationId: String?,
    val assistantId: String?,
    val manager: WorkspaceJobManager,
    val scheduleEngine: JobScheduleEngine,
    val dao: WorkspaceJobDAO,
)

/** 工具名 -> 默认是否需要审批（可在 workspace 设置里覆盖）。 */
val JobToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "job_start" to true,
    "job_run" to true,
    "job_def_create" to true,
    "job_def_update" to true,
)

suspend fun createJobTools(
    ctx: JobToolContext,
    needsApproval: (String) -> Boolean,
): List<Tool> = listOf(
    jobStartTool(ctx, needsApproval),
    jobListTool(ctx),
    jobStatusTool(ctx),
    jobLogsTool(ctx),
    jobWaitTool(ctx),
    jobKillTool(ctx),
    jobRestartTool(ctx),
    jobRemoveTool(ctx),
    jobDefCreateTool(ctx, needsApproval),
    jobDefUpdateTool(ctx, needsApproval),
    jobDefListTool(ctx),
    jobDefRemoveTool(ctx),
    jobRunTool(ctx, needsApproval),
    jobSendTool(ctx),
    jobScreenTool(ctx),
)

// ---------------------------------------------------------------- 运行实例

private fun jobStartTool(ctx: JobToolContext, needsApproval: (String) -> Boolean) = Tool(
    name = "job_start",
    description = (
        "Start a background job in this workspace and return immediately. " +
            "Use def_id to run a saved job definition with args, or command for an ad-hoc job. " +
            "Follow up with job_status / job_logs (incremental cursor) / job_wait, and job_kill to stop. " +
            "mode=pty gives an interactive terminal (job_send / job_screen), mode=pipe is a plain byte log."
        ),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run (required when def_id is not given)")
                })
                put("def_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Saved job definition id or name")
                })
                put("args", buildJsonObject {
                    put("type", "object")
                    put("description", "Arguments for the definition's params")
                })
                put("name", buildJsonObject { put("type", "string") })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory relative to the workspace files root")
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", "pipe (default) or pty")
                })
                put("max_runtime_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Hard runtime limit, default 21600 (6h), max 86400 (24h)")
                })
                put("notify", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Send a system notification when it finishes (default true)")
                })
                put("auto_wake", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Wake this conversation with the result when it finishes (default false)")
                })
                put("env", buildJsonObject {
                    put("type", "object")
                    put("description", "Extra environment variables")
                })
            },
            required = emptyList(),
        )
    },
    needsApproval = { needsApproval("job_start") },
    execute = { element ->
        val params = element.jsonObject
        val command = params.str("command")
        val defRef = params.str("def_id")
        val args = params.stringMap("args")
        val name = params.str("name")
        val cwd = params.str("cwd").orEmpty()
        val mode = WorkspaceJobMode.from(params.str("mode"))
        val maxRuntime = (params.str("max_runtime_seconds")?.toLongOrNull() ?: 21_600L) * 1_000L
        val notify = params.str("notify")?.toBooleanStrictOrNull() ?: true
        val autoWake = params.str("auto_wake")?.toBooleanStrictOrNull() ?: false
        val env = params.stringMap("env")
        runTool {
            if (defRef != null) {
                val def = findDef(ctx, defRef) ?: return@runTool errorJson(
                    "def_not_found",
                    "No job definition named '$defRef' in this workspace",
                )
                val job = ctx.manager.startFromDef(
                    def = def,
                    args = args,
                    triggerSource = JobTriggerSource.AI,
                    conversationId = ctx.conversationId,
                    assistantId = ctx.assistantId,
                    requireKeepAlive = false,
                )
                textJson(job.toJson())
            } else {
                if (command.isNullOrBlank()) {
                    return@runTool errorJson("invalid_params", "Either command or def_id is required")
                }
                val job = ctx.manager.startAdHoc(
                    workspaceId = ctx.workspaceId,
                    command = command,
                    name = name,
                    cwd = cwd,
                    mode = mode,
                    maxRuntimeMs = maxRuntime.coerceAtMost(WorkspaceJobManager.HARD_MAX_RUNTIME_MS),
                    notify = notify,
                    autoWake = autoWake,
                    env = env,
                    conversationId = ctx.conversationId,
                    assistantId = ctx.assistantId,
                    triggerSource = JobTriggerSource.AI,
                )
                textJson(job.toJson())
            }
        }
    },
)

private fun jobListTool(ctx: JobToolContext) = Tool(
    name = "job_list",
    description = "List background jobs of this workspace (newest first), optionally filtered by status.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("status", buildJsonObject {
                    put("type", "string")
                    put("description", "Filter: running, succeeded, failed, killed, timed_out, interrupted, deferred")
                })
                put("limit", buildJsonObject { put("type", "integer") })
            },
            required = emptyList(),
        )
    },
    needsApproval = { false },
    execute = { element ->
        val params = element.jsonObject
        val statusFilter = params.str("status")?.uppercase()
        val limit = (params.str("limit")?.toIntOrNull() ?: 20).coerceIn(1, 100)
        runTool {
            val jobs = ctx.manager.listJobs(ctx.workspaceId, limit)
                .filter { statusFilter == null || it.status == statusFilter }
            textJson(buildJsonObject {
                put("running", ctx.manager.runningCount.value)
                put("limit", WorkspaceJobManager.MAX_CONCURRENT_GLOBAL)
                put("jobs", buildJsonArray { jobs.forEach { add(it.toJson()) } })
            })
        }
    },
)

private fun jobStatusTool(ctx: JobToolContext) = Tool(
    name = "job_status",
    description = "Get one background job: status, exit code, pid, runtime, log sizes and error/deferral reason.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { put("job_id", buildJsonObject { put("type", "string") }) },
            required = listOf("job_id"),
        )
    },
    needsApproval = { false },
    execute = { element ->
        val jobId = element.jsonObject.str("job_id")
        runTool {
            val job = jobId?.let { ctx.manager.getJob(it) }
                ?: return@runTool errorJson("job_not_found", "No such job: $jobId")
            textJson(job.toJson())
        }
    },
)

private fun jobLogsTool(ctx: JobToolContext) = Tool(
    name = "job_logs",
    description = (
        "Read job output incrementally. Pass the returned nextCursor as 'since' to get only new output. " +
            "stream=both returns stdout+stderr; stream=screen returns the pty screen log."
        ),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("job_id", buildJsonObject { put("type", "string") })
                put("stream", buildJsonObject {
                    put("type", "string")
                    put("description", "stdout | stderr | both | screen (default both)")
                })
                put("since", buildJsonObject {
                    put("type", "integer")
                    put("description", "Byte cursor from a previous call (per stream)")
                })
                put("tail_bytes", buildJsonObject {
                    put("type", "integer")
                    put("description", "How much to read at most, default 16384, max 131072")
                })
            },
            required = listOf("job_id"),
        )
    },
    needsApproval = { false },
    execute = { element ->
        val params = element.jsonObject
        val jobId = params.str("job_id")
        val stream = params.str("stream")?.lowercase() ?: "both"
        val since = params.str("since")?.toLongOrNull() ?: 0L
        val maxBytes = (params.str("tail_bytes")?.toIntOrNull() ?: WorkspaceJobManager.DEFAULT_LOG_TAIL_BYTES)
            .coerceIn(256, WorkspaceJobManager.MAX_LOG_READ_BYTES)
        runTool {
            val job = jobId?.let { ctx.manager.getJob(it) }
                ?: return@runTool errorJson("job_not_found", "No such job: $jobId")
            textJson(buildJsonObject {
                put("jobId", job.id)
                put("status", job.status)
                put("cursorHint", "nextCursor values are per stream; pass them back as 'since'")
                if (stream == "stdout" || stream == "both") {
                    put("stdout", chunkJson(ctx.manager.readLog(job.id, JobLogStream.STDOUT, since, maxBytes)))
                }
                if (stream == "stderr" || stream == "both") {
                    put("stderr", chunkJson(ctx.manager.readLog(job.id, JobLogStream.STDERR, since, maxBytes)))
                }
                if (stream == "screen") {
                    put("screen", chunkJson(ctx.manager.readLog(job.id, JobLogStream.SCREEN, since, maxBytes)))
                    put("liveScreen", ctx.manager.screenOf(job.id)?.take(maxBytes) ?: "")
                }
            })
        }
    },
)

private fun jobWaitTool(ctx: JobToolContext) = Tool(
    name = "job_wait",
    description = "Wait for a job to finish (max 600s). Returns the final status plus output tails, or running=true when still busy.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("job_id", buildJsonObject { put("type", "string") })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Default 60, max 600")
                })
                put("tail_bytes", buildJsonObject { put("type", "integer") })
            },
            required = listOf("job_id"),
        )
    },
    needsApproval = { false },
    execute = { element ->
        val params = element.jsonObject
        val jobId = params.str("job_id")
        val timeoutMs = (params.str("timeout_seconds")?.toLongOrNull() ?: DEFAULT_WAIT_SECONDS)
            .coerceIn(1L, MAX_WAIT_SECONDS) * 1_000L
        val tailBytes = (params.str("tail_bytes")?.toIntOrNull() ?: 4_096).coerceIn(256, 65_536)
        runTool {
            val before = jobId?.let { ctx.manager.getJob(it) }
                ?: return@runTool errorJson("job_not_found", "No such job: $jobId")
            val finished = ctx.manager.waitFor(before.id, timeoutMs)
            val job = ctx.manager.getJob(before.id) ?: before
            textJson(buildJsonObject {
                put("jobId", job.id)
                put("status", job.status)
                put("finished", finished)
                job.exitCode?.let { put("exitCode", it) }
                job.runtimeMs?.let { put("runtimeMs", it) }
                if (finished) {
                    put("stdoutTail", tailOf(ctx, job.id, JobLogStream.STDOUT, tailBytes))
                    put("stderrTail", tailOf(ctx, job.id, JobLogStream.STDERR, tailBytes))
                } else {
                    put("hint", "still running; do something else and check later, or job_wait again")
                }
            })
        }
    },
)

private fun jobKillTool(ctx: JobToolContext) = Tool(
    name = "job_kill",
    description = "Stop a running job (TERM then KILL). The job becomes status=killed.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("job_id", buildJsonObject { put("type", "string") })
                put("force", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("job_id"),
        )
    },
    needsApproval = { false },
    execute = { element ->
        val params = element.jsonObject
        val jobId = params.str("job_id")
        val force = params.str("force")?.toBooleanStrictOrNull() ?: false
        runTool {
            val id = jobId ?: return@runTool errorJson("invalid_params", "job_id is required")
            val killed = ctx.manager.kill(id, force)
            if (killed) {
                ctx.manager.waitFor(id, 5_000)
            }
            val job = ctx.manager.getJob(id)
            textJson(buildJsonObject {
                put("jobId", id)
                put("requested", killed)
                put("status", job?.status ?: "UNKNOWN")
            })
        }
    },
)

private fun jobRestartTool(ctx: JobToolContext) = Tool(
    name = "job_restart",
    description = "Run a finished job again as a new job (optionally with a different command or cwd).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("job_id", buildJsonObject { put("type", "string") })
                put("command", buildJsonObject { put("type", "string") })
                put("cwd", buildJsonObject { put("type", "string") })
            },
            required = listOf("job_id"),
        )
    },
    needsApproval = { false },
    execute = { element ->
        val params = element.jsonObject
        val jobId = params.str("job_id")
        runTool {
            val id = jobId ?: return@runTool errorJson("invalid_params", "job_id is required")
            val job = ctx.manager.restart(id, params.str("command"), params.str("cwd"))
                ?: return@runTool errorJson("job_not_found", "No such job: $id")
            textJson(job.toJson())
        }
    },
)

private fun jobRemoveTool(ctx: JobToolContext) = Tool(
    name = "job_remove",
    description = "Delete a finished job record and its logs. Running jobs must be killed first.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { put("job_id", buildJsonObject { put("type", "string") }) },
            required = listOf("job_id"),
        )
    },
    needsApproval = { false },
    execute = { element ->
        val jobId = element.jsonObject.str("job_id")
        runTool {
            val id = jobId ?: return@runTool errorJson("invalid_params", "job_id is required")
            val removed = ctx.manager.removeJob(id)
            if (!removed) {
                return@runTool errorJson("job_running", "Job $id is still running (job_kill it first) or does not exist")
            }
            textJson(buildJsonObject {
                put("jobId", id)
                put("removed", true)
            })
        }
    },
)

// ---------------------------------------------------------------- 任务定义 / 调度

private fun jobDefCreateTool(ctx: JobToolContext, needsApproval: (String) -> Boolean) = Tool(
    name = "job_def_create",
    description = (
        "Create a reusable job definition (template) in this workspace. " +
            "params declare {{name}} placeholders (also exported as RIKKA_PARAM_<NAME>). " +
            "trigger: {\"type\":\"delay\",\"seconds\":600} | {\"type\":\"interval\",\"seconds\":3600} | " +
            "{\"type\":\"once\",\"at\":<epochMillis>} | {\"type\":\"cron\",\"expr\":\"0 9 * * 1-5\",\"tz\":\"Asia/Shanghai\"}."
        ),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string") })
                put("command", buildJsonObject { put("type", "string") })
                put("cwd", buildJsonObject { put("type", "string") })
                put("mode", buildJsonObject { put("type", "string") })
                put("params", buildJsonObject {
                    put("type", "array")
                    put("description", "Array of {name, required, default, description}")
                })
                put("env", buildJsonObject { put("type", "object") })
                put("trigger", buildJsonObject { put("type", "object") })
                put("notify", buildJsonObject { put("type", "boolean") })
                put("auto_wake", buildJsonObject { put("type", "boolean") })
                put("max_runtime_seconds", buildJsonObject { put("type", "integer") })
                put("require_keep_alive", buildJsonObject {
                    put("type", "boolean")
                    put("description", "For scheduled runs: defer when background running is not enabled (default true)")
                })
            },
            required = listOf("name", "command"),
        )
    },
    needsApproval = { needsApproval("job_def_create") },
    execute = { element ->
        val params = element.jsonObject
        runTool {
            val name = params.str("name")?.trim()
            val command = params.str("command")
            if (name.isNullOrBlank() || command.isNullOrBlank()) {
                return@runTool errorJson("invalid_params", "name and command are required")
            }
            if (ctx.dao.getDefByName(ctx.workspaceId, name) != null) {
                return@runTool errorJson("duplicate_name", "A job definition named '$name' already exists")
            }
            val trigger = params.trigger()
            trigger?.let { validateTrigger(it)?.let { err -> return@runTool errorJson("invalid_trigger", err) } }
            val now = System.currentTimeMillis()
            val def = WorkspaceJobDefEntity(
                id = Uuid.random().toString(),
                workspaceId = ctx.workspaceId,
                name = name,
                command = command,
                cwd = params.str("cwd").orEmpty(),
                mode = WorkspaceJobMode.from(params.str("mode")).name,
                paramsJson = JsonInstant.encodeToString(params.paramDtos("params")),
                envJson = JsonInstant.encodeToString(params.stringMap("env")),
                triggerJson = encodeTrigger(trigger),
                enabled = true,
                notify = params.str("notify")?.toBooleanStrictOrNull() ?: true,
                autoWake = params.str("auto_wake")?.toBooleanStrictOrNull() ?: false,
                maxRuntimeMs = ((params.str("max_runtime_seconds")?.toLongOrNull() ?: 21_600L) * 1_000L)
                    .coerceAtMost(WorkspaceJobManager.HARD_MAX_RUNTIME_MS),
                requireKeepAlive = params.str("require_keep_alive")?.toBooleanStrictOrNull() ?: true,
                createdAt = now,
                updatedAt = now,
            )
            ctx.dao.upsertDef(def)
            ctx.scheduleEngine.reschedule(ctx.dao.getDef(def.id) ?: def)
            textJson((ctx.dao.getDef(def.id) ?: def).toJson())
        }
    },
)

private fun jobDefUpdateTool(ctx: JobToolContext, needsApproval: (String) -> Boolean) = Tool(
    name = "job_def_update",
    description = "Update a job definition (partial): command, cwd, mode, params, env, trigger, enabled, notify, auto_wake, max_runtime_seconds.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("def_id", buildJsonObject { put("type", "string") })
                put("name", buildJsonObject { put("type", "string") })
                put("command", buildJsonObject { put("type", "string") })
                put("cwd", buildJsonObject { put("type", "string") })
                put("mode", buildJsonObject { put("type", "string") })
                put("params", buildJsonObject { put("type", "array") })
                put("env", buildJsonObject { put("type", "object") })
                put("trigger", buildJsonObject { put("type", "object") })
                put("enabled", buildJsonObject { put("type", "boolean") })
                put("notify", buildJsonObject { put("type", "boolean") })
                put("auto_wake", buildJsonObject { put("type", "boolean") })
                put("max_runtime_seconds", buildJsonObject { put("type", "integer") })
            },
            required = listOf("def_id"),
        )
    },
    needsApproval = { needsApproval("job_def_update") },
    execute = { element ->
        val params = element.jsonObject
        runTool {
            val defRef = params.str("def_id")
                ?: return@runTool errorJson("invalid_params", "def_id is required")
            val existing = findDef(ctx, defRef)
                ?: return@runTool errorJson("def_not_found", "No job definition '$defRef'")
            val trigger = if (params.containsKey("trigger")) params.trigger() else existing.trigger()
            trigger?.let { validateTrigger(it)?.let { err -> return@runTool errorJson("invalid_trigger", err) } }
            val updated = existing.copy(
                name = params.str("name")?.trim()?.takeIf { it.isNotBlank() } ?: existing.name,
                command = params.str("command") ?: existing.command,
                cwd = params.str("cwd") ?: existing.cwd,
                mode = params.str("mode")?.let { WorkspaceJobMode.from(it).name } ?: existing.mode,
                paramsJson = if (params.containsKey("params")) {
                    JsonInstant.encodeToString(params.paramDtos("params"))
                } else {
                    existing.paramsJson
                },
                envJson = if (params.containsKey("env")) {
                    JsonInstant.encodeToString(params.stringMap("env"))
                } else {
                    existing.envJson
                },
                triggerJson = if (params.containsKey("trigger")) encodeTrigger(trigger) else existing.triggerJson,
                enabled = params.str("enabled")?.toBooleanStrictOrNull() ?: existing.enabled,
                notify = params.str("notify")?.toBooleanStrictOrNull() ?: existing.notify,
                autoWake = params.str("auto_wake")?.toBooleanStrictOrNull() ?: existing.autoWake,
                maxRuntimeMs = params.str("max_runtime_seconds")?.toLongOrNull()?.times(1_000L)
                    ?.coerceAtMost(WorkspaceJobManager.HARD_MAX_RUNTIME_MS) ?: existing.maxRuntimeMs,
                updatedAt = System.currentTimeMillis(),
            )
            ctx.dao.upsertDef(updated)
            ctx.scheduleEngine.reschedule(ctx.dao.getDef(updated.id) ?: updated)
            textJson((ctx.dao.getDef(updated.id) ?: updated).toJson())
        }
    },
)

private fun jobDefListTool(ctx: JobToolContext) = Tool(
    name = "job_def_list",
    description = "List job definitions (templates) of this workspace with trigger and next run time.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {}, required = emptyList())
    },
    needsApproval = { false },
    execute = {
        runTool {
            val defs = ctx.dao.listDefs(ctx.workspaceId)
            textJson(buildJsonObject {
                put("defs", buildJsonArray { defs.forEach { add(it.toJson()) } })
            })
        }
    },
)

private fun jobDefRemoveTool(ctx: JobToolContext) = Tool(
    name = "job_def_remove",
    description = "Delete a job definition (history runs are kept).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { put("def_id", buildJsonObject { put("type", "string") }) },
            required = listOf("def_id"),
        )
    },
    needsApproval = { false },
    execute = { element ->
        val defRef = element.jsonObject.str("def_id")
        runTool {
            val def = defRef?.let { findDef(ctx, it) }
                ?: return@runTool errorJson("def_not_found", "No job definition '$defRef'")
            ctx.scheduleEngine.cancel(def.id)
            ctx.dao.deleteDef(def.id)
            textJson(buildJsonObject {
                put("defId", def.id)
                put("removed", true)
            })
        }
    },
)

private fun jobRunTool(ctx: JobToolContext, needsApproval: (String) -> Boolean) = Tool(
    name = "job_run",
    description = "Run a job definition right now (its schedule stays untouched).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("def_id", buildJsonObject { put("type", "string") })
                put("args", buildJsonObject { put("type", "object") })
            },
            required = listOf("def_id"),
        )
    },
    needsApproval = { needsApproval("job_run") },
    execute = { element ->
        val params = element.jsonObject
        val defRef = params.str("def_id")
        runTool {
            val def = defRef?.let { findDef(ctx, it) }
                ?: return@runTool errorJson("def_not_found", "No job definition '$defRef'")
            val job = ctx.manager.startFromDef(
                def = def,
                args = params.stringMap("args"),
                triggerSource = JobTriggerSource.AI,
                conversationId = ctx.conversationId,
                assistantId = ctx.assistantId,
                requireKeepAlive = false,
            )
            textJson(job.toJson())
        }
    },
)

// ---------------------------------------------------------------- 交互（pty）

private fun jobSendTool(ctx: JobToolContext) = Tool(
    name = "job_send",
    description = "Send input to a pty job (interactive commands). Use keys for control keys such as ctrl-c, tab, enter, up.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("job_id", buildJsonObject { put("type", "string") })
                put("input", buildJsonObject { put("type", "string") })
                put("keys", buildJsonObject {
                    put("type", "string")
                    put("description", "Special key instead of text: ctrl-c, ctrl-d, enter, tab, esc, up, down, left, right")
                })
                put("append_newline", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("job_id"),
        )
    },
    needsApproval = { false },
    execute = { element ->
        val params = element.jsonObject
        val jobId = params.str("job_id")
        runTool {
            val id = jobId ?: return@runTool errorJson("invalid_params", "job_id is required")
            val job = ctx.manager.getJob(id)
                ?: return@runTool errorJson("job_not_found", "No such job: $id")
            if (WorkspaceJobMode.from(job.mode) != WorkspaceJobMode.PTY) {
                return@runTool errorJson("not_pty", "Job $id is mode=pipe; restart it with mode=pty to send input")
            }
            if (!ctx.manager.isRunning(id)) {
                return@runTool errorJson("job_finished", "Job $id already finished (${job.status})")
            }
            val keys = params.str("keys")
            val sent = if (keys != null) {
                ctx.manager.sendKey(id, keys)
            } else {
                ctx.manager.sendInput(id, params.str("input").orEmpty(), params.str("append_newline")?.toBooleanStrictOrNull() ?: true)
            }
            textJson(buildJsonObject {
                put("jobId", id)
                put("sent", sent)
                put("hint", "read the screen with job_screen or job_logs stream=screen")
            })
        }
    },
)

private fun jobScreenTool(ctx: ToolContextAlias) = Tool(
    name = "job_screen",
    description = "Render the current pty screen (like tmux capture-pane) of a pty job.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { put("job_id", buildJsonObject { put("type", "string") }) },
            required = listOf("job_id"),
        )
    },
    needsApproval = { false },
    execute = { element ->
        val jobId = element.jsonObject.str("job_id")
        runTool {
            val id = jobId ?: return@runTool errorJson("invalid_params", "job_id is required")
            val job = ctx.manager.getJob(id)
                ?: return@runTool errorJson("job_not_found", "No such job: $id")
            if (WorkspaceJobMode.from(job.mode) != WorkspaceJobMode.PTY) {
                return@runTool errorJson("not_pty", "Job $id is mode=pipe; use job_logs instead")
            }
            textJson(buildJsonObject {
                put("jobId", id)
                put("status", job.status)
                put("running", ctx.manager.isRunning(id))
                put("screen", ctx.manager.screenOf(id) ?: "")
            })
        }
    },
)

// ---------------------------------------------------------------- helpers

private typealias ToolContextAlias = JobToolContext

private suspend fun runTool(block: suspend () -> List<UIMessagePart>): List<UIMessagePart> =
    try {
        block()
    } catch (e: Exception) {
        errorJson("internal_error", e.message ?: e::class.java.simpleName)
    }

private fun textJson(json: JsonObject): List<UIMessagePart> =
    listOf(UIMessagePart.Text(json.toString()))

private fun errorJson(code: String, message: String): List<UIMessagePart> =
    listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("error", buildJsonObject {
                    put("code", code)
                    put("message", message)
                })
            }.toString()
        )
    )

private suspend fun tailOf(ctx: JobToolContext, jobId: String, stream: JobLogStream, maxBytes: Int): String {
    val total = ctx.manager.readLog(jobId, stream, 0L, WorkspaceJobManager.MAX_LOG_READ_BYTES)?.totalBytes ?: 0L
    val since = (total - maxBytes).coerceAtLeast(0L)
    return ctx.manager.readLog(jobId, stream, since, maxBytes)?.text ?: ""
}

private fun chunkJson(chunk: JobLogChunk?): JsonObject = buildJsonObject {
    if (chunk == null) {
        put("text", "")
        put("nextCursor", 0)
        return@buildJsonObject
    }
    put("text", chunk.text)
    put("from", chunk.from)
    put("to", chunk.to)
    put("totalBytes", chunk.totalBytes)
    put("nextCursor", chunk.to)
    if (chunk.cursorInvalid) put("cursorInvalid", true)
    if (chunk.binary) put("binary", true)
}

private fun WorkspaceJobEntity.toJson(): JsonObject = buildJsonObject {
    put("jobId", id)
    put("name", name)
    put("status", status)
    put("mode", mode)
    put("command", command)
    if (cwd.isNotBlank()) put("cwd", cwd)
    put("createdAt", createdAt)
    startedAt?.let { put("startedAt", it) }
    finishedAt?.let { put("finishedAt", it) }
    runtimeMs?.let { put("runtimeMs", it) }
    exitCode?.let { put("exitCode", it) }
    pid?.let { if (it > 0) put("pid", it) }
    put("logBytesOut", logBytesOut)
    put("logBytesErr", logBytesErr)
    if (logTruncated) put("logTruncated", true)
    error?.let { put("error", it) }
    deferredReason?.let { put("deferredReason", it) }
    defId?.let { put("defId", it) }
    put("autoWake", autoWake)
}

private fun WorkspaceJobDefEntity.toJson(): JsonObject = buildJsonObject {
    put("defId", id)
    put("name", name)
    put("command", command)
    if (cwd.isNotBlank()) put("cwd", cwd)
    put("mode", mode)
    put("enabled", enabled)
    val paramArray = buildJsonArray {
        params().forEach { param ->
            add(buildJsonObject {
                put("name", param.name)
                put("required", param.required)
                param.default?.let { put("default", it) }
                param.description?.let { put("description", it) }
            })
        }
    }
    put("params", paramArray)
    triggerJson?.let { put("trigger", it) }
    nextRunAt?.let { put("nextRunAt", it) }
    lastRunAt?.let { put("lastRunAt", it) }
    put("runCount", runCount)
    lastStatus?.let { put("lastStatus", it) }
    put("notify", notify)
    put("autoWake", autoWake)
    put("requireKeepAlive", requireKeepAlive)
}

private suspend fun findDef(ctx: JobToolContext, idOrName: String): WorkspaceJobDefEntity? =
    ctx.dao.getDef(idOrName) ?: ctx.dao.getDefByName(ctx.workspaceId, idOrName)

private fun validateTrigger(trigger: JobTrigger): String? = when (trigger) {
    is JobTrigger.Cron -> CronExpression.validate(trigger.expr)
    is JobTrigger.Interval -> if (trigger.seconds < 60) "interval must be at least 60 seconds" else null
    is JobTrigger.Delay -> if (trigger.seconds < 1) "delay must be positive" else null
    is JobTrigger.Once -> if (trigger.at <= System.currentTimeMillis()) "once.at must be in the future (epoch millis)" else null
}

private fun JsonObject.str(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.stringMap(name: String): Map<String, String> {
    val obj = this[name] as? JsonObject ?: return emptyMap()
    return obj.entries.mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
    }.toMap()
}

private fun JsonObject.paramDtos(name: String): List<JobParamDto> {
    val array = this[name] as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val paramName = obj.str("name") ?: return@mapNotNull null
        JobParamDto(
            name = paramName,
            required = obj.str("required")?.toBooleanStrictOrNull() ?: false,
            default = obj.str("default"),
            description = obj.str("description"),
        )
    }
}

private fun JsonObject.trigger(): JobTrigger? {
    val obj = this["trigger"] as? JsonObject ?: return null
    return when (obj.str("type")?.lowercase()) {
        "delay" -> obj.str("seconds")?.toLongOrNull()?.let { JobTrigger.Delay(it) }
        "interval" -> obj.str("seconds")?.toLongOrNull()
            ?.let { JobTrigger.Interval(it, obj.str("start_at")?.toLongOrNull()) }
        "once" -> obj.str("at")?.toLongOrNull()?.let { JobTrigger.Once(it) }
        "cron" -> obj.str("expr")?.let { JobTrigger.Cron(it, obj.str("tz")) }
        else -> null
    }
}
