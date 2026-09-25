package me.rerere.rikkahub.data.workflow

import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.dao.WorkflowDAO
import me.rerere.rikkahub.data.db.entity.WorkflowEntity
import me.rerere.rikkahub.data.db.entity.WorkflowRunEntity
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.run.RunStatus
import me.rerere.rikkahub.utils.JsonInstant
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "WorkflowRunEngine"

sealed interface WorkflowStartResult {
    data class Started(val runId: String) : WorkflowStartResult
    data class Rejected(val error: String) : WorkflowStartResult
}

/**
 * 工作流运行编排: 读步骤快照 -> 逐步执行 -> 落状态 -> 按 on_error 决策。
 *
 * 一次运行整条流程只占一个协程, 步骤本身是各自独立的 shell 任务（见 WorkflowRunner）。
 * 因此"停止整个流程" = 取消这个协程（当前步骤的任务由引擎的取消检查收尾）。
 */
class WorkflowRunEngine(
    /**
     * 依赖具体类型 AppScope 而不是 CoroutineScope 抽象 —— DI 里 AppScope 是单类型注册的,
     * 用抽象去 get() 会解析不到（真机启动崩过两次, 别再改成 CoroutineScope）。
     */
    private val scope: AppScope,
    private val dao: WorkflowDAO,
    private val runner: WorkflowRunner,
) {
    private val runs = ConcurrentHashMap<String, Job>()

    fun isRunning(runId: String): Boolean = runs[runId]?.isActive == true

    fun runningCount(): Int = runs.values.count { it.isActive }

    /**
     * 启动一次运行。
     *
     * 必填参数缺失或步骤 id 重复会**拒绝启动**: 让流程跑到一半才失败, 比直接说清楚更糟。
     */
    suspend fun start(
        workflow: WorkflowEntity,
        args: Map<String, String>,
        conversationId: String?,
        assistantId: String?,
        triggerSource: String,
    ): WorkflowStartResult {
        val declared = runCatching {
            JsonInstant.decodeFromString<List<WorkflowParam>>(workflow.paramsJson)
        }.getOrDefault(emptyList())
        val (params, missing) = WorkflowVarResolver.resolveParams(declared, args)
        if (missing.isNotEmpty()) {
            return WorkflowStartResult.Rejected("missing required param(s): " + missing.joinToString(", "))
        }
        val steps = runCatching {
            JsonInstant.decodeFromString<List<WorkflowStep>>(workflow.stepsJson)
        }.getOrDefault(emptyList())
        if (steps.isEmpty()) return WorkflowStartResult.Rejected("workflow has no steps")

        val duplicate = steps.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicate.isNotEmpty()) {
            return WorkflowStartResult.Rejected("duplicate step id(s): " + duplicate.joinToString(", "))
        }

        val runId = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val initialState = runCatching {
            JsonInstant.encodeToString(steps.map { WorkflowStepState(id = it.id) })
        }.getOrDefault("[]")
        val run = WorkflowRunEntity(
            id = runId,
            workflowId = workflow.id,
            workspaceId = workflow.workspaceId,
            conversationId = conversationId,
            assistantId = assistantId,
            title = workflow.description?.takeIf { it.isNotBlank() } ?: workflow.name,
            argsJson = runCatching { JsonInstant.encodeToString(params) }.getOrDefault("{}"),
            resolvedStepsJson = workflow.stepsJson,
            stepStatesJson = initialState,
            status = RunStatus.RUNNING.name,
            triggerSource = triggerSource,
            createdAt = now,
            startedAt = now,
            notify = workflow.notify,
            autoWake = workflow.autoWake,
        )
        dao.upsertRun(run)

        val job = scope.launch {
            execute(run = run, steps = steps, params = params, workflowEnv = workflow.env())
        }
        runs[runId] = job
        return WorkflowStartResult.Started(runId)
    }

    /** 停止一次运行; 不在内存里时把账收敛掉（否则界面永远停在"运行中"）。 */
    suspend fun kill(runId: String): Boolean {
        val job = runs[runId]
        if (job != null) {
            job.cancel()
            return true
        }
        val run = dao.getRun(runId) ?: return false
        if (RunStatus.from(run.status).isFinished) return false
        val finishedAt = System.currentTimeMillis()
        dao.upsertRun(
            run.copy(
                status = RunStatus.INTERRUPTED.name,
                finishedAt = finishedAt,
                runtimeMs = finishedAt - (run.startedAt ?: finishedAt),
                error = "run lost (stopped from UI)",
            )
        )
        return true
    }

    /** App 启动时收敛遗留。 */
    suspend fun reconcile(): Int {
        runs.clear()
        val pending = dao.listUnfinishedRuns()
        if (pending.isEmpty()) return 0
        dao.markUnfinishedAs(
            status = RunStatus.INTERRUPTED.name,
            finishedAt = System.currentTimeMillis(),
            error = "run lost (app killed)",
        )
        return pending.size
    }

    private suspend fun execute(
        run: WorkflowRunEntity,
        steps: List<WorkflowStep>,
        params: Map<String, String>,
        workflowEnv: Map<String, String>,
    ) {
        val states = steps.associate { it.id to WorkflowStepState(id = it.id) }.toMutableMap()
        var failure: String? = null
        var aborted = false

        for (step in steps) {
            if (aborted) {
                states[step.id] = states.getValue(step.id).copy(status = WorkflowStepStatus.SKIPPED)
                continue
            }
            val stepOutputs = states.mapValues { (_, s) -> s.outputs }
            val rendered = WorkflowVarResolver.render(step.command, params + step.params, stepOutputs)
            if (rendered.unresolved.isNotEmpty()) {
                failure = "step " + step.id + " has unresolved placeholder(s): " +
                    rendered.unresolved.joinToString(", ")
                states[step.id] = states.getValue(step.id).copy(
                    status = WorkflowStepStatus.FAILED,
                    error = failure,
                )
                if (step.onError == StepOnError.FAIL) aborted = true
                persist(run, states, step.id)
                continue
            }
            val renderedEnv = step.env.mapValues { (_, v) ->
                WorkflowVarResolver.render(v, params + step.params, stepOutputs).text
            }
            states[step.id] = states.getValue(step.id).copy(
                status = WorkflowStepStatus.RUNNING,
                startedAt = System.currentTimeMillis(),
            )
            persist(run, states, step.id)

            val result = runner.runStep(
                workspaceId = run.workspaceId,
                runId = run.id,
                step = step,
                command = rendered.text,
                env = workflowEnv + renderedEnv,
                conversationId = run.conversationId,
                assistantId = run.assistantId,
                defaultTimeoutMs = DEFAULT_STEP_TIMEOUT_MS,
                isCancelled = { runs[run.id]?.isActive != true },
            )
            states[step.id] = states.getValue(step.id).copy(
                status = result.status,
                jobId = result.jobId,
                exitCode = result.exitCode,
                attempts = result.attempts,
                error = result.error,
                finishedAt = System.currentTimeMillis(),
                outputs = result.outputs,
            )
            persist(run, states, step.id)

            if (!result.isSuccess) {
                failure = failure ?: ("step " + step.id + ": " + (result.error ?: result.status.name))
                if (step.onError == StepOnError.FAIL) aborted = true
            }
        }

        val status = when {
            failure == null -> RunStatus.SUCCEEDED
            states.values.any { it.status == WorkflowStepStatus.KILLED } -> RunStatus.KILLED
            else -> RunStatus.FAILED
        }
        runs.remove(run.id)
        persist(run, states, null, status, failure)
        Log.i(TAG, "workflow run " + run.id + " finished: " + status)
    }

    private suspend fun persist(
        run: WorkflowRunEntity,
        states: Map<String, WorkflowStepState>,
        currentStep: String?,
        finalStatus: RunStatus? = null,
        error: String? = null,
    ) {
        val fresh = dao.getRun(run.id) ?: run
        val ordered = runCatching {
            JsonInstant.decodeFromString<List<WorkflowStep>>(run.resolvedStepsJson)
        }.getOrDefault(emptyList()).mapNotNull { states[it.id] }
        val finishedAt = if (finalStatus != null) System.currentTimeMillis() else null
        dao.upsertRun(
            fresh.copy(
                stepStatesJson = runCatching { JsonInstant.encodeToString(ordered) }
                    .getOrDefault(fresh.stepStatesJson),
                currentStep = currentStep,
                status = (finalStatus ?: RunStatus.from(fresh.status)).name,
                finishedAt = finishedAt ?: fresh.finishedAt,
                runtimeMs = finishedAt?.let { it - (fresh.startedAt ?: it) } ?: fresh.runtimeMs,
                error = error ?: fresh.error,
            )
        )
    }

    private fun WorkflowEntity.env(): Map<String, String> =
        runCatching { JsonInstant.decodeFromString<Map<String, String>>(envJson) }.getOrDefault(emptyMap())

    companion object {
        /** 单步默认上限: 工作流可能有很多步, 单步给 30 分钟比较稳妥。 */
        const val DEFAULT_STEP_TIMEOUT_MS = 30L * 60 * 1000
    }
}
