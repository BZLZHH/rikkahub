package me.rerere.rikkahub.data.run

import android.util.Log
import me.rerere.rikkahub.AppScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "RunOrchestrator"

/**
 * 后台执行的编排层。
 *
 * 只做与执行体无关的事: 并发配额、看门狗超时、取消收尾、代际防覆盖、启动时遗留清理。
 *
 * **硬约束**: 本类里不允许出现 pid / 进程 / PRoot / 信号 等字样。一旦出现, 说明抽象漏了 ——
 * 那些属于执行体（见 [RunExecutor]）。shell 任务的"杀进程树"在 ShellRunExecutor 里,
 * 子代理的"停模型循环"在 AgentRunExecutor 里。
 */
class RunOrchestrator(
    /**
     * 依赖具体类型 [AppScope] 而不是 CoroutineScope 抽象 —— DI 里 AppScope 是单类型注册的,
     * 用抽象去 get() 会解析不到(真机启动崩溃过一次, 别改成 CoroutineScope)。
     */
    private val scope: AppScope,
    /** 读取用户配置的配额; 每次调用都重新读, 所以设置改完立刻生效。 */
    private val quotaReader: suspend () -> RunQuotaConfig,
    /** run 结束时回调（通知 / 唤醒会话等由上层接） */
    private val onRunFinished: (RunRecord) -> Unit = {},
) {
    private val executors = ConcurrentHashMap<RunKind, RunExecutor>()
    private val registries = ConcurrentHashMap<RunKind, RunRegistry>()
    private val active = ConcurrentHashMap<String, ActiveRun>()
    private val generation = AtomicLong(0L)

    private val _activeCounts = MutableStateFlow<Map<RunKind, Int>>(emptyMap())

    /** 各执行体当前在跑的数量（UI 徽标 / 配额提示用）。 */
    val activeCounts: StateFlow<Map<RunKind, Int>> = _activeCounts.asStateFlow()

    fun register(executor: RunExecutor, registry: RunRegistry) {
        executors[executor.kind] = executor
        registries[executor.kind] = registry
    }

    fun isRunning(runId: String): Boolean = active.containsKey(runId)

    fun countOf(kind: RunKind): Int = active.values.count { it.record.kind == kind }

    fun activeRun(runId: String): ActiveRun? = active[runId]

    /** 该 run 是否已被要求停止（执行体据此分辨"被杀"与"自己失败"）。 */
    fun stopRequested(runId: String): Boolean = active[runId]?.stopRequested == true

    fun timedOut(runId: String): Boolean = active[runId]?.timedOut == true

    /** 超时终止用的等待时长; 句柄消失时由编排层兜底。 */
    private fun currentDeadline(record: RunRecord): Long =
        (record.startedAt ?: System.currentTimeMillis()) + record.maxRuntimeMs

    suspend fun start(record: RunRecord): RunLaunchResult {
        val executor = executors[record.kind]
            ?: return RunLaunchResult.NotStarted("no executor registered for " + record.kind)
        val registry = registries[record.kind]
            ?: return RunLaunchResult.NotStarted("no registry registered for " + record.kind)

        if (!canStart(record.kind, record.workspaceId)) {
            return RunLaunchResult.NotStarted(quotaMessage(record.kind, record.workspaceId))
        }

        val run = ActiveRun(record = record, generation = generation.incrementAndGet())
        active[record.runId] = run
        bumpCounts()
        runCatching { registry.register(record) }
            .onFailure { Log.e(TAG, "register failed for " + record.runId, it) }

        val result = runCatching { executor.launch(run, registry) }
            .getOrElse { RunLaunchResult.NotStarted(it.message ?: "launch failed") }

        val started = result as? RunLaunchResult.Started
        if (started == null) {
            // 没起来: 收尾记账, 不留悬挂条目
            val failure = result as? RunLaunchResult.NotStarted
            active.remove(record.runId)
            bumpCounts()
            runCatching {
                registry.updateStatus(
                    runId = record.runId,
                    status = RunStatus.FAILED,
                    finishedAt = System.currentTimeMillis(),
                    error = failure?.error ?: "launch failed",
                )
            }
            return failure ?: RunLaunchResult.NotStarted("launch failed")
        }

        watch(run, executor, registry, started.handle)
        return started
    }

    /**
     * 登记一次"已经启动了"的执行。
     *
     * 用于调用方必须先起进程、再拿句柄的场景（shell 任务要先拿到真实 pid 才能落库）。
     * 配额**不在这里判** —— 调用方负责在最外层判过, 这里只做记账与看护。
     */
    fun attachExisting(
        record: RunRecord,
        registry: RunRegistry,
        handleProvider: () -> RunHandle?,
    ): RunHandle? {
        val handle = handleProvider() ?: return null
        val run = ActiveRun(record = record, generation = generation.incrementAndGet())
        run.handle = handle
        active[record.runId] = run
        bumpCounts()
        // 看护: 等句柄结束。状态的持久化仍由执行体那套负责（shell 的收尾逻辑要写 exit_code
        // 与日志大小, 不该由编排层代劳）; 这里只做计数与回调。
        scope.launch {
            handle.await(record.maxRuntimeMs)
            val current = active[record.runId]
            if (current == null || current.generation != run.generation) return@launch
            active.remove(record.runId)
            bumpCounts()
            // 回调要带**真实**结果: 直接问账本, 不要假设成功 ——
            // 否则失败/被杀的任务会向通知与唤醒谎报 SUCCEEDED。
            val finalStatus = runCatching { registry.statusOf(record.runId) }.getOrNull()
                ?: RunStatus.SUCCEEDED
            runCatching { onRunFinished(record.copy(status = finalStatus)) }
        }
        return handle
    }

    /** 终止一次执行。 */
    suspend fun kill(runId: String, force: Boolean = false): Boolean {
        val run = active[runId]
        if (run != null) {
            run.stopRequested = true
            run.cancel?.cancel()
            val handle = run.handle ?: return false
            return runCatching {
                handle.terminate(if (force) 0L else DEFAULT_TERMINATE_GRACE_MS)
                true
            }.getOrDefault(false)
        }
        // 句柄不在了: 记录可能还挂在 RUNNING（App 重启等）。交给执行体自查,
        // 能收掉进程树就收掉, 收不掉也要把状态收敛, 否则界面会永远停在"运行中"。
        val registry = registries.values.firstOrNull { reg ->
            runCatching { reg.listUnfinished().any { it.runId == runId } }.getOrDefault(false)
        } ?: return false
        // 交给该执行体清理自己的遗留（它才认识自己的进程/协程）;
        // 没有执行体就退回"仅收敛状态"。
        val executor = executors[registry.kind]
        return runCatching {
            if (executor != null) {
                executor.reconcileRegistry(registry)
            } else {
                registry.updateStatus(
                    runId = runId,
                    status = RunStatus.INTERRUPTED,
                    finishedAt = System.currentTimeMillis(),
                    error = "process lost (stopped from UI)",
                )
                1
            }
        }.getOrDefault(0) > 0 || active.containsKey(runId)
    }

    /** 等待一次执行结束; 返回是否在超时前结束。 */
    suspend fun waitFor(runId: String, timeoutMillis: Long): Boolean {
        val run = active[runId]
        val handle = run?.handle
        if (handle != null) return handle.await(timeoutMillis)
        // 不在内存里: 只要账上已经不是"未结束", 就当作已结束
        return registries.values.none { reg ->
            runCatching { reg.listUnfinished().any { it.runId == runId } }.getOrDefault(false)
        }
    }

    /** App 启动时: 让每种执行体清理自己的遗留。 */
    suspend fun reconcileOnStart(): Int {
        var total = 0
        executors.values.forEach { executor ->
            val registry = registries[executor.kind] ?: return@forEach
            total += runCatching { executor.reconcileRegistry(registry) }
                .onFailure { Log.e(TAG, "reconcile failed for " + executor.kind, it) }
                .getOrDefault(0)
        }
        return total
    }

    // ---- 内部 ----

    /** 配额是否允许再起一个该 kind 的执行（配额由用户设置, 每次现读）。 */
    suspend fun canStart(kind: RunKind, workspaceId: String): Boolean {
        val config = quotaReader()
        if (countOf(kind) >= config.maxGlobal(kind)) return false
        val inWorkspace = active.values.count {
            it.record.kind == kind && it.record.workspaceId == workspaceId
        }
        return inWorkspace < config.maxPerWorkspace(kind)
    }

    /** 给用户/AI 看的配额说明（用于报错文案）。 */
    suspend fun quotaMessage(kind: RunKind, workspaceId: String): String {
        val config = quotaReader()
        val label = kindLabel(kind)
        val inWorkspace = active.values.count {
            it.record.kind == kind && it.record.workspaceId == workspaceId
        }
        return if (countOf(kind) >= config.maxGlobal(kind)) {
            "quota_exceeded: " + config.maxGlobal(kind) + " " + label +
                "(s) already running (raise the limit in settings, or stop one first)"
        } else {
            "quota_exceeded: " + config.maxPerWorkspace(kind) + " " + label +
                "(s) already running in this workspace (limit " + inWorkspace + "/" +
                config.maxPerWorkspace(kind) + ")"
        }
    }

    private fun kindLabel(kind: RunKind): String = when (kind) {
        RunKind.JOB -> "job"
        RunKind.AGENT -> "subagent"
    }

    /**
     * 看门狗 + 收尾。
     *
     * 只等"句柄结束"这一件事: 等待本身由执行体实现（等进程 / 等模型循环）。
     * 到 [RunRecord.maxRuntimeMs] 仍未结束则强制终止并记 TIMED_OUT。
     */
    private fun watch(run: ActiveRun, executor: RunExecutor, registry: RunRegistry, handle: RunHandle) {
        val job = scope.launch {
            val finished = handle.await(run.record.maxRuntimeMs)
            if (!finished) {
                run.timedOut = true
                run.stopRequested = true
                run.cancel?.cancel()
                runCatching { handle.terminate(0L) }
                    .onFailure { Log.w(TAG, "force terminate failed for " + run.record.runId, it) }
                // 强杀后仍等一小段, 让执行体自己收尾
                handle.await(FORCE_KILL_SETTLE_MS)
            }
            finalize(run, executor, registry)
        }
        // 超时兜底: 句柄的 await 若不返回, 这里保证状态不会永远停在 RUNNING
        scope.launch {
            val remaining = currentDeadline(run.record) - System.currentTimeMillis()
            if (remaining > 0) kotlinx.coroutines.delay(remaining)
            if (active.containsKey(run.record.runId) && !run.stopRequested) {
                Log.w(TAG, "watchdog firing for " + run.record.runId + " (handle.await did not return in time)")
                run.timedOut = true
                run.stopRequested = true
                run.cancel?.cancel()
                runCatching { run.handle?.terminate(0L) }
            }
        }
        run.cancel = job
    }

    private suspend fun finalize(run: ActiveRun, executor: RunExecutor, registry: RunRegistry) {
        val current = active[run.record.runId]
        if (current == null || current.generation != run.generation) {
            // 已被新一代顶替: 旧执行体不得再改记录
            return
        }
        active.remove(run.record.runId)
        bumpCounts()

        val status = when {
            run.timedOut -> RunStatus.TIMED_OUT
            run.stopRequested -> RunStatus.KILLED
            run.record.error != null -> RunStatus.FAILED
            else -> RunStatus.SUCCEEDED
        }
        val finishedAt = System.currentTimeMillis()
        val record = run.record.copy(
            status = status,
            finishedAt = finishedAt,
            error = if (status == RunStatus.SUCCEEDED) null else run.record.error,
        )
        runCatching { registry.updateStatus(run.record.runId, status, finishedAt, record.error) }
            .onFailure { Log.e(TAG, "updateStatus failed for " + run.record.runId, it) }
        runCatching { executor.onFinish(run, status, null, record.error) }
            .onFailure { Log.e(TAG, "onFinish failed for " + run.record.runId, it) }
        runCatching { onRunFinished(record) }
            .onFailure { Log.e(TAG, "onRunFinished callback failed", it) }
    }

    private fun bumpCounts() {
        _activeCounts.value = RunKind.entries.associateWith { kind ->
            active.values.count { it.record.kind == kind }
        }
    }

    companion object {
        /** kill() 的默认宽限期: 给执行体一点时间自己收干净。 */
        const val DEFAULT_TERMINATE_GRACE_MS = 1_500L

        /** 强杀之后再等执行体收尾的时间。 */
        const val FORCE_KILL_SETTLE_MS = 1_000L
    }
}

/** 用户可调的配额快照。 */
data class RunQuotaConfig(
    val maxConcurrentJobs: Int,
    val maxConcurrentJobsPerWorkspace: Int,
    val maxConcurrentAgents: Int,
    val maxConcurrentAgentsPerWorkspace: Int,
) {
    fun maxGlobal(kind: RunKind): Int = RunQuota.clampGlobal(
        when (kind) {
            RunKind.JOB -> maxConcurrentJobs
            RunKind.AGENT -> maxConcurrentAgents
        }
    )

    fun maxPerWorkspace(kind: RunKind): Int = RunQuota.clampPerWorkspace(
        when (kind) {
            RunKind.JOB -> maxConcurrentJobsPerWorkspace
            RunKind.AGENT -> maxConcurrentAgentsPerWorkspace
        }
    )
}
