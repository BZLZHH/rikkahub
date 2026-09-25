package me.rerere.rikkahub.data.workflow

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkflowRunDAO
import me.rerere.rikkahub.data.db.entity.WorkflowAgentEntity
import me.rerere.rikkahub.data.db.entity.WorkflowAgentResultEntity
import me.rerere.rikkahub.data.db.entity.WorkflowRunEntity
import me.rerere.rikkahub.data.run.RunStatus
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "WorkflowRunner"

/** 起一次 workflow 运行所需的全部输入。 */
data class WorkflowRunRequest(
    val workspaceId: String,
    val conversationId: String? = null,
    val assistantId: String? = null,
    /** 内联脚本; 与 [name] 二选一 */
    val source: String? = null,
    /** 已保存的脚本名（/workspace/.rhe/workflows/<name>.js）; 与 [source] 二选一 */
    val name: String? = null,
    /** 内联脚本额外存一份, 供以后按名复用 */
    val saveAs: String? = null,
    val args: JsonElement = JsonObject(emptyMap()),
    val cwd: String? = null,
    val maxSteps: Int = 20,
    val timeoutMillis: Long = WorkflowRuntime.DEFAULT_TIMEOUT_MS,
)

sealed interface WorkflowStartResult {
    data class Started(
        val runId: String,
        val name: String,
        val fingerprint: String,
        val scriptPath: String?,
    ) : WorkflowStartResult

    /** 没起来: 脚本不存在 / 语法外的问题（如 import）/ 名字非法。 */
    data class Rejected(val error: String) : WorkflowStartResult
}

/**
 * workflow 运行服务: 起一次运行、跟进进度、杀掉它。
 *
 * 为什么**不阻塞**在工具调用里等它跑完: 一个 workflow 可能跑几十分钟, 把模型的一次工具调用
 * 挂在那里等, 会话就废了。形态与 subagent_* 一致 —— 起、查、停三件套。
 * 与子代理不同的是: workflow 的进度与结果是**给模型看的一段文本**,
 * 而中间那些 agent 的产出留在脚本变量与存档里, 不进任何上下文。
 */
class WorkflowRunner(
    private val scope: AppScope,
    private val dao: WorkflowRunDAO,
    private val invoker: WorkflowAgentInvoker,
    private val scripts: WorkflowScriptStore,
    private val settingsStore: SettingsStore,
) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val completions = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    fun isRunning(runId: String): Boolean = jobs[runId]?.isActive == true

    suspend fun start(request: WorkflowRunRequest): WorkflowStartResult {
        // 1) 取脚本文本: 按名加载 或 用内联的那份
        val source = when {
            !request.name.isNullOrBlank() -> runCatching {
                scripts.load(request.workspaceId, request.name)
            }.getOrElse { return WorkflowStartResult.Rejected(describe(it)) }
                ?: return WorkflowStartResult.Rejected(
                    "no saved workflow named " + request.name +
                        " (saved workflows live in " + WorkflowScriptStore.DIR + ")"
                )

            !request.source.isNullOrBlank() -> request.source
            else -> return WorkflowStartResult.Rejected("either script or name is required")
        }

        val script = WorkflowScriptParser.parse(source)
        // 启动前的静态体检: 有些问题能一眼看出来, 就别浪费一次真实运行
        WorkflowScriptParser.rejectionOf(script)?.let { return WorkflowStartResult.Rejected(it) }

        // 2) 需要的话把内联脚本存下来（"跑一次就顺手存成命令"）
        val scriptPath = if (!request.saveAs.isNullOrBlank()) {
            runCatching { scripts.save(request.workspaceId, request.saveAs, source) }
                .getOrElse { return WorkflowStartResult.Rejected(describe(it)) }
        } else {
            request.name?.takeIf { it.isNotBlank() }?.let { WorkflowScriptStore.pathOf(it) }
        }

        val runId = Uuid.random().toString()
        val now = System.currentTimeMillis()
        dao.upsert(
            WorkflowRunEntity(
                id = runId,
                workspaceId = request.workspaceId,
                conversationId = request.conversationId,
                assistantId = request.assistantId,
                name = script.displayName,
                fingerprint = script.fingerprint,
                scriptPath = scriptPath,
                status = RunStatus.RUNNING.name,
                createdAt = now,
                startedAt = now,
            )
        )

        val completion = CompletableDeferred<Unit>()
        completions[runId] = completion
        val job = scope.launch {
            try {
                val result = executeScript(runId, request, script)
                withContext(NonCancellable) {
                    val finishedAt = System.currentTimeMillis()
                    val row = dao.getById(runId)
                    dao.upsert(
                        (row ?: return@withContext).copy(
                            status = if (result.ok) RunStatus.SUCCEEDED.name else RunStatus.FAILED.name,
                            resultJson = result.resultJson,
                            error = result.error,
                            agentsCalled = result.agentsCalled,
                            agentsReused = result.agentsReused,
                            finishedAt = finishedAt,
                            runtimeMs = finishedAt - (row.startedAt ?: finishedAt),
                        )
                    )
                }
            } catch (e: CancellationException) {
                // 被停止: 停止理由要落库, 否则界面会永远停在"运行中"
                withContext(NonCancellable) {
                    val finishedAt = System.currentTimeMillis()
                    dao.getById(runId)?.let { row ->
                        dao.upsert(
                            row.copy(
                                status = RunStatus.KILLED.name,
                                error = row.error ?: "stopped",
                                finishedAt = finishedAt,
                                runtimeMs = finishedAt - (row.startedAt ?: finishedAt),
                            )
                        )
                    }
                }
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "workflow run " + runId + " crashed", e)
                withContext(NonCancellable) {
                    val finishedAt = System.currentTimeMillis()
                    dao.getById(runId)?.let { row ->
                        dao.upsert(
                            row.copy(
                                status = RunStatus.FAILED.name,
                                error = describe(e),
                                finishedAt = finishedAt,
                                runtimeMs = finishedAt - (row.startedAt ?: finishedAt),
                            )
                        )
                    }
                }
            } finally {
                jobs.remove(runId)
                completion.complete(Unit)
                completions.remove(runId)
            }
        }
        jobs[runId] = job

        return WorkflowStartResult.Started(
            runId = runId,
            name = script.displayName,
            fingerprint = script.fingerprint,
            scriptPath = scriptPath,
        )
    }

    /** 真正跑脚本: 组装运行时、接上进度落库与可恢复存档, 并按用户配的并发开闸。 */
    private suspend fun executeScript(
        runId: String,
        request: WorkflowRunRequest,
        script: WorkflowScript,
    ): WorkflowExecutionResult {
        val progress = WorkflowRunProgress()
        // 脚本声明的 phases 先摆进左栏 —— 这样"还没跑到的阶段"也能看见,
        // 而不是等第一个 agent 起来才凭空冒出来一个分组。
        progress.seedPhases(script.meta?.phases.orEmpty())
        val tracker = DaoAgentTracker(runId)
        val flusher = scope.launch {
            while (isActive) {
                delay(PROGRESS_FLUSH_MS)
                flushProgress(runId, progress, tracker)
            }
        }
        try {
            val agentConcurrency = settingsStore.settingsFlowRaw.first().maxConcurrentAgents
            val runtime = WorkflowRuntime(
                agentHandler = WorkflowAgentHandler { call, observer ->
                    invoker.run(
                        call = call,
                        observer = observer,
                        conversationId = request.conversationId,
                        assistantId = request.assistantId,
                        cwd = request.cwd,
                        maxSteps = request.maxSteps,
                    )
                },
                progress = progress,
                timeoutMillis = request.timeoutMillis,
                journal = DaoWorkflowJournal(dao),
                agentConcurrency = agentConcurrency,
                tracker = tracker,
            )
            return runtime.execute(script, request.args)
        } finally {
            flusher.cancel()
            withContext(NonCancellable) { flushProgress(runId, progress, tracker) }
        }
    }

/**
     * 把逐 agent 的行写进库。
     *
     * 分工: 高频的**过程**（token / 步骤）先攒在内存, 由 [PROGRESS_FLUSH_MS] 的刷子统一写;
     * 低频的**起止**（start / finish）立刻写 —— 那两个时刻正是 UI 最需要及时反映的。
     * 每个 chunk 都写一次数据库会把库打爆, 而 token 数并不需要毫秒级准确。
     */
    private inner class DaoAgentTracker(private val runId: String) : WorkflowAgentTracker {
        private val live = ConcurrentHashMap<Int, LiveAgentState>()

        override suspend fun start(call: WorkflowAgentCall, reused: Boolean): WorkflowAgentObserver {
            val state = LiveAgentState()
            live[call.callIndex] = state
            runCatching {
                dao.upsertAgent(
                    WorkflowAgentEntity(
                        runId = runId,
                        callIndex = call.callIndex,
                        label = call.label,
                        prompt = call.prompt,
                        phase = call.phase,
                        // 复用命中的行一出生就是完成态: 它确实"完成"了, 只是没花时间
                        status = if (reused) RunStatus.SUCCEEDED.name else RunStatus.RUNNING.name,
                        reused = reused,
                        startedAt = System.currentTimeMillis(),
                    )
                )
            }.onFailure { Log.w(TAG, "agent row insert failed for " + runId + "#" + call.callIndex, it) }

            return object : WorkflowAgentObserver {
                override fun onModel(modelId: String, modelName: String) {
                    state.modelId = modelId
                    state.modelName = modelName
                    state.dirty = true
                }

                override fun onProgress(steps: Int, toolCalls: Int, tokensIn: Int, tokensOut: Int) {
                    state.steps = steps
                    state.toolCalls = toolCalls
                    state.tokensIn = tokensIn
                    state.tokensOut = tokensOut
                    state.dirty = true
                }

                override fun onSession(sessionId: String) {
                    state.sessionId = sessionId
                    state.dirty = true
                }
            }
        }

        override suspend fun finish(
            call: WorkflowAgentCall,
            reused: Boolean,
            status: String,
            resultText: String?,
            error: String?,
        ) {
            val state = live.remove(call.callIndex)
            runCatching {
                val existing = dao.getAgent(runId, call.callIndex)
                val finishedAt = System.currentTimeMillis()
                val startedAt = existing?.startedAt ?: finishedAt
                dao.upsertAgent(
                    (existing ?: WorkflowAgentEntity(
                        runId = runId,
                        callIndex = call.callIndex,
                        label = call.label,
                        prompt = call.prompt,
                        phase = call.phase,
                        status = status,
                    )).copy(
                        status = status,
                        reused = reused,
                        modelId = state?.modelId ?: existing?.modelId,
                        modelName = state?.modelName ?: existing?.modelName,
                        steps = state?.steps ?: 0,
                        toolCalls = state?.toolCalls ?: 0,
                        tokensIn = state?.tokensIn ?: 0,
                        tokensOut = state?.tokensOut ?: 0,
                        sessionId = state?.sessionId ?: existing?.sessionId,
                        resultText = resultText,
                        error = error,
                        finishedAt = finishedAt,
                        runtimeMs = finishedAt - startedAt,
                    )
                )
            }.onFailure { Log.w(TAG, "agent row finish failed for " + runId + "#" + call.callIndex, it) }
        }

        /** 把"过程中变化过"的行刷一遍。没有变化的行不写。 */
        suspend fun flushDirty() {
            live.forEach { (index, state) ->
                if (!state.dirty) return@forEach
                runCatching {
                    val existing = dao.getAgent(runId, index) ?: return@forEach
                    dao.updateAgent(
                        existing.copy(
                            modelId = state.modelId ?: existing.modelId,
                            modelName = state.modelName ?: existing.modelName,
                            steps = state.steps,
                            toolCalls = state.toolCalls,
                            tokensIn = state.tokensIn,
                            tokensOut = state.tokensOut,
                            sessionId = state.sessionId ?: existing.sessionId,
                        )
                    )
                    state.dirty = false
                }.onFailure { Log.w(TAG, "agent row flush failed for " + runId + "#" + index, it) }
            }
        }
    }

    /** 把内存里攒的进度刷进库。频率由 [PROGRESS_FLUSH_MS] 控制, 不是每条日志一次 UPDATE。 */
    private suspend fun flushProgress(
        runId: String,
        progress: WorkflowRunProgress,
        tracker: DaoAgentTracker? = null,
    ) {
        runCatching {
            dao.getById(runId)?.let { row ->
                dao.upsert(
                    row.copy(
                        phase = progress.phase ?: row.phase,
                        progressJson = progress.snapshotJson(),
                        phasesJson = progress.phasesJson(),
                        agentsCalled = progress.agentsCalled,
                        agentsReused = progress.agentsReused,
                    )
                )
            }
        }.onFailure { Log.w(TAG, "progress flush failed for " + runId, it) }
        tracker?.flushDirty()
    }

    /** 等一次运行结束; 返回是否在超时前结束。 */
    suspend fun await(runId: String, timeoutMillis: Long): Boolean {
        val completion = completions[runId]
        if (completion == null) {
            // 不在内存里: 只要账上已经不是"未结束", 就当作已结束
            val row = dao.getById(runId) ?: return true
            return RunStatus.from(row.status).isFinished
        }
        return withTimeoutOrNullCompat(timeoutMillis) { completion.await(); true } ?: false
    }

    /** 停止一次运行; 返回是否真的发出过停止（已经结束返回 false）。 */
    suspend fun kill(runId: String): Boolean {
        val job = jobs[runId]
        if (job != null) {
            job.cancel(CancellationException("workflow stopped by request"))
            return true
        }
        // 句柄不在了但账上还没结束（App 重启过）: 收敛成中断, 别让界面永远停在运行中
        val row = dao.getById(runId) ?: return false
        if (!RunStatus.from(row.status).isFinished) {
            val finishedAt = System.currentTimeMillis()
            dao.upsert(
                row.copy(
                    status = RunStatus.INTERRUPTED.name,
                    error = row.error ?: "process lost (stopped)",
                    finishedAt = finishedAt,
                    runtimeMs = finishedAt - (row.startedAt ?: finishedAt),
                )
            )
            return true
        }
        return false
    }

    /** App 启动时: 协程随进程一起没了, 把残留的"未结束"收敛成中断。 */
    suspend fun reconcileOnStart(): Int {
        val unfinished = dao.listByStatus(
            listOf(RunStatus.PENDING.name, RunStatus.RUNNING.name)
        )
        val finishedAt = System.currentTimeMillis()
        unfinished.forEach { row ->
            runCatching {
                dao.upsert(
                    row.copy(
                        status = RunStatus.INTERRUPTED.name,
                        error = row.error ?: "interrupted (app restarted)",
                        finishedAt = finishedAt,
                        runtimeMs = finishedAt - (row.startedAt ?: finishedAt),
                    )
                )
            }
        }
        return unfinished.size
    }

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.java.name

    private suspend fun <T> withTimeoutOrNullCompat(
        timeoutMillis: Long,
        block: suspend () -> T,
    ): T? = kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) { block() }

    /** 把可恢复存档接到 Room 上。 */
    private class DaoWorkflowJournal(private val dao: WorkflowRunDAO) : WorkflowJournal {
        override suspend fun recall(fingerprint: String, callIndex: Int): WorkflowAgentRecord? =
            dao.recallAgent(fingerprint, callIndex)?.let {
                WorkflowAgentRecord(
                    callIndex = it.callIndex,
                    prompt = it.prompt,
                    schemaJson = it.schemaJson,
                    label = it.label,
                    resultText = it.resultText,
                )
            }

        override suspend fun remember(fingerprint: String, record: WorkflowAgentRecord) {
            dao.rememberAgent(
                WorkflowAgentResultEntity(
                    fingerprint = fingerprint,
                    callIndex = record.callIndex,
                    prompt = record.prompt,
                    schemaJson = record.schemaJson,
                    label = record.label,
                    resultText = record.resultText,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    companion object {
        /** 进度刷库间隔。每条日志都写一次 UPDATE 会把数据库打爆, 而进度本来也不需要毫秒级。 */
        const val PROGRESS_FLUSH_MS = 500L

        /** 时间线只保尾部: 它是给人看的进度, 不是审计日志。 */
        const val MAX_TIMELINE_ENTRIES = 200
    }
}

/**
 * 运行进度的内存缓冲。
 *
 * 进度回调是**同步**的（从 JS 宿主函数里直接调回来）, 而写库是 suspend ——
 * 所以这里只攒不发, 由 [WorkflowRunner] 的定时器统一刷。
 */
private class WorkflowRunProgress : WorkflowProgressSink {
    private val lock = Any()
    private val entries = ArrayDeque<JsonObject>()

    @Volatile
    var phase: String? = null
        private set

    @Volatile
    var agentsCalled: Int = 0
        private set

    @Volatile
    var agentsReused: Int = 0
        private set

    /** 左栏的顺序: 脚本声明的在前, 实际走过的按首次出现追加。 */
    private val phases = mutableListOf<String>()

    override fun onPhase(title: String) {
        synchronized(lock) {
            phase = title
            if (title.isNotBlank() && title !in phases) phases.add(title)
            add("phase", title)
        }
    }

    /** 脚本 run 之前先把 meta.phases 摆进来 —— 空阶段也要看得见。 */
    fun seedPhases(declared: List<String>) {
        synchronized(lock) {
            declared.filter { it.isNotBlank() }.forEach { if (it !in phases) phases.add(it) }
        }
    }

    fun phasesJson(): String = synchronized(lock) {
        JsonArray(phases.map { JsonPrimitive(it) }).toString()
    }

    override fun onLog(message: String, level: String) {
        synchronized(lock) { add(level, message) }
    }

    override fun onAgentProgress(started: Int, reused: Int) {
        agentsCalled = started
        agentsReused = reused
    }

    fun snapshotJson(): String = synchronized(lock) { JsonArray(entries.toList()).toString() }

    private fun add(kind: String, text: String) {
        entries.addLast(
            buildJsonObject {
                put("t", System.currentTimeMillis())
                put("kind", kind)
                put("text", text)
            }
        )
        while (entries.size > WorkflowRunner.MAX_TIMELINE_ENTRIES) entries.removeFirst()
    }
}

/**
 * 一个正在跑的 agent 的**过程**状态。
 *
 * 只活在内存里, 由 [WorkflowRunner.DaoAgentTracker] 定期刷进库。
 * 全用 @Volatile: 写它的线程是子代理的收集协程, 读它的线程是刷库的定时器。
 */
private class LiveAgentState {
    @Volatile var modelId: String? = null
    @Volatile var modelName: String? = null
    @Volatile var steps: Int = 0
    @Volatile var toolCalls: Int = 0
    @Volatile var tokensIn: Int = 0
    @Volatile var tokensOut: Int = 0
    @Volatile var sessionId: String? = null
    @Volatile var dirty: Boolean = true
}
