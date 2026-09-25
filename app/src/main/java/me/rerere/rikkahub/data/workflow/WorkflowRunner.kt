package me.rerere.rikkahub.data.workflow

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.job.WorkspaceJobManager
import me.rerere.rikkahub.data.job.WorkspaceJobMode
import me.rerere.rikkahub.data.job.WorkspaceJobStatus
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File

private const val TAG = "WorkflowRunner"

/** 一步的执行结果，供引擎决定"继续还是中止"。 */
data class WorkflowStepResult(
    val stepId: String,
    val status: WorkflowStepStatus,
    val jobId: String?,
    val exitCode: Int?,
    val attempts: Int,
    val error: String?,
    val outputs: Map<String, String>,
) {
    val isSuccess: Boolean get() = status == WorkflowStepStatus.SUCCEEDED
}

/**
 * 工作流引擎。
 *
 * 核心设计: **一步就是一个 shell 后台任务**（`job_start` 那套）。
 * 这样每一步都自动获得任务系统已有的能力 —— 日志落盘与增量读取、任务页可见、
 * 能被用户单独停止、App 被杀后照样收敛 —— 不必再造一套"步骤执行器"。
 *
 * 步骤之间用文件传值（独立进程没有共享内存）:
 * 引擎为每步注入 `RIKKA_OUT` 指向一个临时 JSON 文件, 步骤写它,
 * 引擎按 `export.keys` 取出并暴露为 `{{steps.<id>.<key>}}`。
 */
class WorkflowRunner(
    private val jobManager: WorkspaceJobManager,
    private val workspaceRepository: WorkspaceRepository,
    /** 每步 RIKKA_OUT 文件的落盘目录（在 app 私有区, 由 -b 映射进工作区） */
    private val outputsBaseDir: File,
) {

    /**
     * 跑一步（含重试）。
     *
     * 重试会起**新的任务**, 不复用上一个 —— 复用会掩盖真实失败次数, 日志也会混在一起。
     */
    suspend fun runStep(
        workspaceId: String,
        runId: String,
        step: WorkflowStep,
        command: String,
        env: Map<String, String>,
        conversationId: String?,
        assistantId: String?,
        defaultTimeoutMs: Long,
        isCancelled: () -> Boolean,
    ): WorkflowStepResult {
        val maxAttempts = (step.retries + 1).coerceAtLeast(1)
        var attempts = 0
        var lastExit: Int? = null
        var lastJobId: String? = null
        var lastError: String? = null

        val outFile = File(File(outputsBaseDir, runId).apply { mkdirs() }, step.id + ".json")
        runCatching { outFile.delete() }

        while (attempts < maxAttempts) {
            if (isCancelled()) {
                return WorkflowStepResult(
                    stepId = step.id,
                    status = WorkflowStepStatus.KILLED,
                    jobId = lastJobId,
                    exitCode = null,
                    attempts = attempts,
                    error = "cancelled",
                    outputs = emptyMap(),
                )
            }
            attempts++
            val stepEnv = env + mapOf(
                "RIKKA_OUT" to outFile.absolutePath,
                "RIKKA_STEP_ID" to step.id,
                "RIKKA_RUN_ID" to runId,
            )
            val timeoutMs = step.timeoutSeconds?.let { it * 1000 } ?: defaultTimeoutMs
            val job = runCatching {
                jobManager.startAdHoc(
                    workspaceId = workspaceId,
                    command = command,
                    name = step.id,
                    reason = "workflow step " + step.id,
                    cwd = step.cwd,
                    mode = WorkspaceJobMode.from(step.mode),
                    maxRuntimeMs = timeoutMs,
                    notify = false,
                    env = stepEnv,
                    conversationId = conversationId,
                    assistantId = assistantId,
                )
            }.getOrElse { error ->
                return WorkflowStepResult(
                    stepId = step.id,
                    status = WorkflowStepStatus.FAILED,
                    jobId = null,
                    exitCode = null,
                    attempts = attempts,
                    error = error.message ?: "failed to start step",
                    outputs = emptyMap(),
                )
            }
            lastJobId = job.id
            jobManager.waitFor(job.id, timeoutMs + WAIT_GRACE_MS)
            val finished = jobManager.getJob(job.id)
            lastExit = finished?.exitCode
            val status = WorkspaceJobStatus.from(finished?.status)
            when (status) {
                WorkspaceJobStatus.SUCCEEDED -> {
                    val outputs = readExports(outFile, step.export) +
                        WorkflowVarResolver.builtinStepKeys(finished?.exitCode)
                    return WorkflowStepResult(
                        stepId = step.id,
                        status = WorkflowStepStatus.SUCCEEDED,
                        jobId = job.id,
                        exitCode = finished?.exitCode,
                        attempts = attempts,
                        error = null,
                        outputs = outputs,
                    )
                }
                WorkspaceJobStatus.KILLED -> return WorkflowStepResult(
                    stepId = step.id,
                    status = WorkflowStepStatus.KILLED,
                    jobId = job.id,
                    exitCode = finished?.exitCode,
                    attempts = attempts,
                    error = "step was killed",
                    outputs = emptyMap(),
                )
                WorkspaceJobStatus.TIMED_OUT -> lastError = "step timed out"
                WorkspaceJobStatus.INTERRUPTED -> lastError = "step was interrupted (app killed)"
                else -> lastError = finished?.error ?: ("exit code " + lastExit)
            }
            if (attempts < maxAttempts) {
                Log.w(TAG, "step " + step.id + " attempt " + attempts + " failed (" + lastError + "), retrying")
            }
        }

        return WorkflowStepResult(
            stepId = step.id,
            status = WorkflowStepStatus.FAILED,
            jobId = lastJobId,
            exitCode = lastExit,
            attempts = attempts,
            error = lastError ?: "step failed",
            outputs = emptyMap(),
        )
    }

    /** 读取步骤写出的 JSON, 按 export.keys 取出需要的键。读不到就返回空（不是错误）。 */
    private fun readExports(file: File, export: WorkflowExport?): Map<String, String> {
        if (export == null || export.keys.isEmpty()) return emptyMap()
        if (!file.isFile) return emptyMap()
        val parsed = runCatching {
            JsonInstant.parseToJsonElement(file.readText()) as? JsonObject
        }.getOrNull() ?: return emptyMap()
        return export.keys.mapNotNull { key ->
            val value = parsed[key]?.jsonPrimitive?.contentOrNull
            if (value == null) null else key to value
        }.toMap()
    }

    companion object {
        /** 任务结束到落库之间留一点余量, 避免刚好卡在边界上。 */
        const val WAIT_GRACE_MS = 5_000L
    }
}
