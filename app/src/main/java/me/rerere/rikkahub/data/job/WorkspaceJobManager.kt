package me.rerere.rikkahub.data.job

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.dao.WorkspaceJobDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceJobDefEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceJobEntity
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.BackgroundKeepAliveService
import me.rerere.rikkahub.data.run.RunHandle
import me.rerere.rikkahub.data.run.RunKind
import me.rerere.rikkahub.data.run.RunOrchestrator
import me.rerere.rikkahub.data.run.RunQuota
import me.rerere.rikkahub.data.run.RunRecord
import me.rerere.rikkahub.data.run.RunStatus
import me.rerere.workspace.JobParamResolver
import me.rerere.workspace.RunningWorkspaceJob
import me.rerere.workspace.WorkspaceManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "WorkspaceJobManager"

/**
 * 后台任务管理器: job_* 工具、UI 与调度器共用的唯一入口。
 *
 * 职责: 并发配额、进程生命周期（超时/停止）、日志落盘、状态持久化、完成事件。
 * 不负责: 定时触发的计算（[JobScheduleEngine]）、完成后的通知与唤醒
 * （[me.rerere.rikkahub.service.WorkspaceJobNotificationManager] / [JobWakeCoordinator]）。
 */
class WorkspaceJobManager(
    private val context: Context,
    private val appScope: AppScope,
    private val dao: WorkspaceJobDAO,
    private val workspaceDao: WorkspaceDAO,
    private val workspaceManager: WorkspaceManager,
    private val settingsStore: SettingsStore,
    private val eventBus: AppEventBus,
    /** 执行编排层: shell 任务与子代理共用它的配额 / 看门狗 / 取消收尾。 */
    private val orchestrator: RunOrchestrator,
) {
    private val runRegistry = ShellRunRegistry(dao)

    init {
        // 把 shell 执行体接进编排层。注意这里传 this —— 本类就是 shell 执行体的实现载体。
        orchestrator.register(ShellRunExecutor(this), runRegistry)
    }

    companion object {
        const val MAX_CONCURRENT_GLOBAL = 4
        const val MAX_CONCURRENT_PER_WORKSPACE = 2
        const val DEFAULT_MAX_RUNTIME_MS = 6L * 60 * 60 * 1000
        const val HARD_MAX_RUNTIME_MS = 24L * 60 * 60 * 1000
        const val DEFAULT_LOG_TAIL_BYTES = 16 * 1024
        const val MAX_LOG_READ_BYTES = 128 * 1024

        /** pty 任务的屏幕轮询间隔 */
        const val PTY_POLL_INTERVAL_MS = 1_000L
    }

    /** 统一的"可终止句柄": pipe 是 PRoot 进程, pty 是终端会话。 */
    private fun interface JobHandle {
        fun terminate(graceMillis: Long)
    }

    private class RunningEntry(
        val workspaceRoot: String,
        val handle: JobHandle,
        val stdout: java.io.OutputStream?,
        val stderr: java.io.OutputStream?,
        /** 这一代进程的唯一标识: 同一 jobId 被重跑后, 旧进程收尾时不能覆盖新进程的行。 */
        val generation: Long,
        /** 真实子进程 pid(取不到为 -1): 内存句柄丢失时用它兜底结束进程。 */
        val pid: Long,
        var timedOut: Boolean = false,
    )

    private val logStore = JobLogStore(File(context.filesDir, "workspace-jobs"))

    /** PRoot 进程的定位与结束 —— 属于 shell 执行体, 不属于编排层。 */
    private val processControl = ShellProcessControl(context)
    private val running = ConcurrentHashMap<String, RunningEntry>()
    private val ptyJobs = ConcurrentHashMap<String, PtyJobSession>()
    private val killRequested = ConcurrentHashMap.newKeySet<String>()
    private val processGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    private val _runningCount = MutableStateFlow(0)

    /** 当前运行中的任务数（UI 用）。 */
    val runningCount: StateFlow<Int> = _runningCount.asStateFlow()

    fun logs(workspaceRoot: String): JobLogStore = logStore

    // ---- 生命周期 ----

    /**
     * App 启动时调用: 收掉上一进程遗留的 RUNNING 任务, 顺带补跑 deferred。
     *
     * 这些任务的进程不一定会随 App 一起死: PRoot 以 --kill-on-exit 清子树的前提是它自己能正常退出,
     * App 被系统直接杀掉时它就没了这个机会, 子树(bash/python3 等)会变成孤儿继续跑、继续占端口。
     * 所以这里先按落库的 pid 把孤儿树收掉, 再改状态 —— 否则界面上任务显示"被中断", 后台却还在跑。
     */
    suspend fun reconcileOnStart() {
        val now = System.currentTimeMillis()
        runCatching {
            dao.listRunningJobs().forEach { stale ->
                val killedProcess = processControl.killByPid(stale.pid)
                if (killedProcess) Log.w(TAG, "reconcileOnStart: killed leftover process tree for " + stale.id)
            }
        }.onFailure { Log.e(TAG, "reconcileOnStart: killing leftovers failed", it) }
        runCatching {
            val reaped = processControl.reapOrphanLaunchers()
            if (reaped > 0) Log.w(TAG, "reconcileOnStart: reaped " + reaped + " orphan proot launcher(s)")
        }.onFailure { Log.e(TAG, "reconcileOnStart: orphan sweep failed", it) }
        runCatching {
            dao.markRunningAs(
                status = WorkspaceJobStatus.INTERRUPTED.name,
                exitCode = null,
                error = "process lost (app killed)",
                finishedAt = now,
            )
        }.onFailure { Log.e(TAG, "reconcileOnStart failed", it) }
        runDeferredJobs()
    }

    /** 常驻开启后补跑被系统拦下的定时任务。 */
    suspend fun runDeferredJobs(): Int {
        val enabled = runCatching { settingsStore.settingsFlowRaw.first().backgroundRunningEnabled }
            .getOrDefault(false)
        if (!enabled) return 0
        val deferred = dao.listDeferredJobs()
        var started = 0
        deferred.forEach { job ->
            val workspace = workspaceDao.getById(job.workspaceId) ?: return@forEach
            if (!workspaceManager.hasRootfs(workspace.root)) return@forEach
            if (orchestrator.countOf(RunKind.JOB) >= RunQuota.DEFAULT_MAX_CONCURRENT_JOBS) return@forEach
            val resolved = resolveDeferred(job)
            runCatching { launchProcess(job.copy(deferredReason = null), workspace, resolved.first, resolved.second) }
                .onSuccess { started++ }
                .onFailure { Log.e(TAG, "resume deferred job ${job.id} failed", it) }
        }
        return started
    }

    private suspend fun resolveDeferred(job: WorkspaceJobEntity): Pair<String, Map<String, String>> {
        val def = job.defId?.let { dao.getDef(it) } ?: return job.command to emptyMap()
        val args = runCatching {
            me.rerere.rikkahub.utils.JsonInstant.decodeFromString<Map<String, String>>(job.argsJson)
        }.getOrDefault(emptyMap())
        val resolved = JobParamResolver.resolve(def.command, def.params().toParamDefs(), args)
        return resolved.command to (def.env() + resolved.env)
    }

    // ---- 启动 ----

    suspend fun startAdHoc(
        workspaceId: String,
        command: String,
        name: String? = null,
        reason: String? = null,
        cwd: String = "",
        mode: WorkspaceJobMode = WorkspaceJobMode.PIPE,
        maxRuntimeMs: Long = DEFAULT_MAX_RUNTIME_MS,
        notify: Boolean = true,
        autoWake: Boolean = false,
        env: Map<String, String> = emptyMap(),
        conversationId: String? = null,
        assistantId: String? = null,
        triggerSource: JobTriggerSource = JobTriggerSource.AI,
    ): WorkspaceJobEntity {
        val workspace = workspaceDao.getById(workspaceId) ?: error("Workspace not found: $workspaceId")
        require(workspaceManager.hasRootfs(workspace.root)) { "Rootfs is not installed for this workspace" }

        val now = System.currentTimeMillis()
        val created = WorkspaceJobEntity(
            id = Uuid.random().toString(),
            workspaceId = workspaceId,
            name = name?.takeIf { it.isNotBlank() } ?: deriveName(command),
            reason = reason?.takeIf { it.isNotBlank() },
            command = command,
            cwd = cwd,
            mode = mode.name,
            status = WorkspaceJobStatus.PENDING.name,
            triggerSource = triggerSource.name,
            createdAt = now,
            conversationId = conversationId,
            assistantId = assistantId,
            notify = notify,
            autoWake = autoWake,
            maxRuntimeMs = maxRuntimeMs.coerceIn(1_000L, HARD_MAX_RUNTIME_MS),
        )
        dao.upsertJob(created)
        return launchViaOrchestrator(created, workspace, command, env)
    }

    /** 由任务定义（模板）启动一次运行。 */
    suspend fun startFromDef(
        def: WorkspaceJobDefEntity,
        args: Map<String, String> = emptyMap(),
        triggerSource: JobTriggerSource = JobTriggerSource.SCHEDULE,
        conversationId: String? = null,
        assistantId: String? = null,
        requireKeepAlive: Boolean = def.requireKeepAlive,
    ): WorkspaceJobEntity {
        val workspace = workspaceDao.getById(def.workspaceId)
            ?: error("Workspace not found: ${def.workspaceId}")

        if (triggerSource == JobTriggerSource.SCHEDULE && requireKeepAlive) {
            val keepAliveEnabled = runCatching {
                settingsStore.settingsFlowRaw.first().backgroundRunningEnabled
            }.getOrDefault(false)
            if (!keepAliveEnabled) {
                // 后台起前台服务在 Android 12+ 可能被系统拒绝, 与其跑一半被杀, 不如标记延后
                val deferred = WorkspaceJobEntity(
                    id = Uuid.random().toString(),
                    workspaceId = def.workspaceId,
                    defId = def.id,
                    name = def.name,
                    reason = def.description,
                    command = def.command,
                    cwd = def.cwd,
                    argsJson = runCatching { me.rerere.rikkahub.utils.JsonInstant.encodeToString(args) }
                        .getOrDefault("{}"),
                    mode = def.mode,
                    status = WorkspaceJobStatus.DEFERRED.name,
                    triggerSource = JobTriggerSource.SCHEDULE.name,
                    createdAt = System.currentTimeMillis(),
                    notify = def.notify,
                    autoWake = def.autoWake,
                    maxRuntimeMs = def.maxRuntimeMs,
                    deferredReason = "background foreground-service start is restricted; waiting for keep-alive",
                )
                dao.upsertJob(deferred)
                eventBus.tryEmit(
                    AppEvent.WorkspaceJobDeferred(deferred.id, def.workspaceId, def.name)
                )
                return deferred
            }
        }

        val resolved = JobParamResolver.resolve(def.command, def.params().toParamDefs(), args)
        val require = resolved.missing
        require(require.isEmpty()) { "Missing required params: ${require.joinToString(", ")}" }

        val now = System.currentTimeMillis()
        val created = WorkspaceJobEntity(
            id = Uuid.random().toString(),
            workspaceId = def.workspaceId,
            defId = def.id,
            name = def.name,
            reason = def.description,
            command = resolved.command,
            cwd = def.cwd,
            argsJson = runCatching { me.rerere.rikkahub.utils.JsonInstant.encodeToString(args) }
                .getOrDefault("{}"),
            mode = def.mode,
            status = WorkspaceJobStatus.PENDING.name,
            triggerSource = triggerSource.name,
            createdAt = now,
            conversationId = conversationId,
            assistantId = assistantId,
            notify = def.notify,
            autoWake = def.autoWake,
            maxRuntimeMs = def.maxRuntimeMs.coerceIn(1_000L, HARD_MAX_RUNTIME_MS),
        )
        dao.upsertJob(created)
        return launchViaOrchestrator(created, workspace, resolved.command, def.env() + resolved.env)
    }

    private fun deriveName(command: String): String =
        command.lineSequence().firstOrNull()?.trim()?.take(60)?.takeIf { it.isNotEmpty() } ?: "job"


    private fun workspaceIdOf(root: String): String? =
        workspaceManager.workspaceDir(root).name

    private fun bumpRunning() {
        _runningCount.value = running.size
    }

    /** 把内存里的运行条目包装成编排层要的句柄。 */
    private fun RunningEntry.toRunHandle(entryJobId: String): RunHandle = object : RunHandle {
        override suspend fun terminate(graceMillis: Long) {
            // 双路收尾: 先按 pid 收进程树(能连 PRoot 子树一起收干净), 再退回句柄 destroy。
            // PRoot 自身不一定把子进程带走, 只 destroy 句柄会留下还在跑的 bash/python。
            val pidKilled = processControl.killByPid(pid)
            val handleOk = runCatching {
                handle.terminate(graceMillis)
                true
            }.getOrDefault(false)
            Log.i(TAG, "handle.terminate pid=" + pid + " pidKilled=" + pidKilled + " handleOk=" + handleOk)
        }

        override suspend fun await(timeoutMillis: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                if (!running.containsKey(entryJobId)) return true
                kotlinx.coroutines.delay(100)
            }
            return !running.containsKey(entryJobId)
        }

        override val isAlive: Boolean get() = running.containsKey(entryJobId)
    }

    // ---- 编排层接入 ----

    /**
     * 启动一个 job, 但**配额由编排层判**（这样它才能和子代理分账）。
     *
     * 保留"配额不足就抛异常"的语义: job_* 工具与调度器都依赖这个行为向用户/AI 报错。
     */
    private suspend fun launchViaOrchestrator(
        job: WorkspaceJobEntity,
        workspace: WorkspaceEntity,
        command: String,
        env: Map<String, String>,
    ): WorkspaceJobEntity {
        // 配额必须在起进程之前判: 以前是 checkQuota() 直接 require, 调用方(job_* 工具、调度器)
        // 依赖这个抛错来上报; 现在判定逻辑统一在编排层, 但语义保持不变。
        if (!orchestrator.canStart(RunKind.JOB, job.workspaceId)) {
            error(orchestrator.quotaMessage(RunKind.JOB, job.workspaceId))
        }
        val started = launchProcess(job, workspace, command, env)
        // 记账要在**起好之后**: RunRecord 直接取真实实体的状态与起始时间, 不靠假设。
        // 若起进程就失败了, launchProcess 已把 FAILED 落库, 这里 attach 到的也是真实结果。
        orchestrator.attachExisting(started.toRunRecord(), runRegistry) { handleFor(started.id) }
        return started
    }

    /**
     * 编排层驱动的启动入口（由 [ShellRunExecutor] 调用）。
     *
     * 与 [launchViaOrchestrator] 的区别: 配额已由编排层判过, 这里**不再记账**
     * （否则会在编排器里出现两条同 runId 的记录）。但仍要起进程并挂句柄。
     */
    internal suspend fun launchFromOrchestrator(
        record: RunRecord,
        skipQuotaCheck: Boolean = true,
    ): WorkspaceJobEntity {
        val job = dao.getJob(record.runId) ?: error("job not found: " + record.runId)
        if (running.containsKey(job.id)) return job
        val workspace = workspaceDao.getById(job.workspaceId)
            ?: error("Workspace not found: " + job.workspaceId)
        val def = job.defId?.let { dao.getDef(it) }
        val args = runCatching {
            me.rerere.rikkahub.utils.JsonInstant.decodeFromString<Map<String, String>>(job.argsJson)
        }.getOrDefault(emptyMap())
        val command = def?.let {
            JobParamResolver.resolve(it.command, it.params().toParamDefs(), args).command
        } ?: job.command
        val env = def?.let { it.env() } ?: emptyMap()
        return launchProcess(job, workspace, command, env)
    }

    /** 取某个 job 的运行句柄（编排层用）。 */
    fun handleFor(jobId: String): RunHandle? = running[jobId]?.toRunHandle(jobId)

    /** 编排层通报"这一代已结束"。 */
    fun onOrchestratedRunFinished(jobId: String, status: RunStatus) {
        Log.i(TAG, "orchestrator finished job=" + jobId + " status=" + status)
    }

    /** 收掉遗留进程树（启动清理用; 顺序: 先收进程, 再改状态）。 */
    suspend fun killLeftoverProcesses() {
        runCatching {
            dao.listRunningJobs().forEach { stale ->
                if (processControl.killByPid(stale.pid)) {
                    Log.w(TAG, "killLeftoverProcesses: killed leftover process tree for " + stale.id)
                }
            }
        }.onFailure { Log.e(TAG, "killLeftoverProcesses failed", it) }
        runCatching {
            val reaped = processControl.reapOrphanLaunchers()
            if (reaped > 0) Log.w(TAG, "killLeftoverProcesses: reaped " + reaped + " orphan launcher(s)")
        }.onFailure { Log.e(TAG, "killLeftoverProcesses: orphan sweep failed", it) }
    }

    // ---- 执行 ----

    private suspend fun launchProcess(
        job: WorkspaceJobEntity,
        workspace: WorkspaceEntity,
        command: String,
        env: Map<String, String>,
    ): WorkspaceJobEntity {
        val startedAt = System.currentTimeMillis()
        val generation = processGeneration.incrementAndGet()
        val started = job.copy(
            status = WorkspaceJobStatus.RUNNING.name,
            startedAt = startedAt,
            finishedAt = null,
            runtimeMs = null,
            exitCode = null,
            error = null,
            deferredReason = null,
        )
        dao.upsertJob(started)
        eventBus.tryEmit(AppEvent.WorkspaceJobStarted(started.id, started.workspaceId, started.name))
        ensureKeepAlive()

        return if (WorkspaceJobMode.from(started.mode) == WorkspaceJobMode.PTY) {
            startPtyJob(started, workspace, command, env, generation)
        } else {
            startPipeJob(started, workspace, command, env, generation)
        }
    }

    private suspend fun startPipeJob(
        job: WorkspaceJobEntity,
        workspace: WorkspaceEntity,
        command: String,
        env: Map<String, String>,
        generation: Long,
    ): WorkspaceJobEntity {
        val stdoutFile = logStore.streamFile(workspace.root, job.id, JobLogStream.STDOUT)
        val stderrFile = logStore.streamFile(workspace.root, job.id, JobLogStream.STDERR)
        var outStream: java.io.OutputStream? = null
        var errStream: java.io.OutputStream? = null
        val process = try {
            outStream = logStore.openAppend(stdoutFile)
            errStream = logStore.openAppend(stderrFile)
            workspaceManager.startJob(
                root = workspace.root,
                command = command,
                cwd = job.cwd,
                stdout = outStream,
                stderr = errStream,
                env = env,
                shellCompatibilityMode = workspace.shellCompatibilityMode,
            )
        } catch (e: Exception) {
            runCatching { outStream?.close() }
            runCatching { errStream?.close() }
            val failed = job.copy(
                status = WorkspaceJobStatus.FAILED.name,
                exitCode = 127,
                error = e.message ?: "failed to start process",
                finishedAt = System.currentTimeMillis(),
            )
            dao.upsertJob(failed)
            emitFinished(failed)
            return failed
        }

        val pid = processControl.resolveLauncherPidWithRetry(process.pid)
        Log.i(TAG, "job " + job.id + " launched, process.pid=" + process.pid + " resolvedPid=" + pid)
        processControl.diag("launch job=" + job.id + " process.pid=" + process.pid + " resolvedPid=" + pid)
        if (pid > 0L) {
            val withPid = job.copy(pid = pid)
            runCatching { dao.upsertJob(withPid) }
                .onFailure { Log.w(TAG, "failed to persist pid for job " + job.id, it) }
        } else {
            Log.w(TAG, "could not resolve pid for job " + job.id + "; stop-after-restart falls back to sweep")
        }
        running[job.id] = RunningEntry(
            workspaceRoot = workspace.root,
            handle = JobHandle { grace -> process.terminate(grace) },
            stdout = outStream,
            stderr = errStream,
            generation = generation,
            pid = pid,
        )
        bumpRunning()
        watchProcess(job, workspace, process, generation)
        return dao.getJob(job.id) ?: job
    }

    private suspend fun startPtyJob(
        job: WorkspaceJobEntity,
        workspace: WorkspaceEntity,
        command: String,
        env: Map<String, String>,
        generation: Long,
    ): WorkspaceJobEntity {
        val session = try {
            PtyJobSession.create(
                context = context,
                workspaceRoot = workspace.root,
                jobId = job.id,
                command = command,
                cwd = job.cwd,
                env = env,
                shellCompatibilityMode = workspace.shellCompatibilityMode,
                logStore = logStore,
            )
        } catch (e: Exception) {
            val failed = job.copy(
                status = WorkspaceJobStatus.FAILED.name,
                exitCode = 127,
                error = e.message ?: "failed to start pty session",
                finishedAt = System.currentTimeMillis(),
            )
            dao.upsertJob(failed)
            emitFinished(failed)
            return failed
        }
        ptyJobs[job.id] = session
        running[job.id] = RunningEntry(
            workspaceRoot = workspace.root,
            handle = JobHandle { session.kill() },
            stdout = null,
            stderr = null,
            generation = generation,
            pid = -1L,
        )
        bumpRunning()
        watchPty(job, workspace, session, generation)
        return dao.getJob(job.id) ?: job
    }

    private fun watchProcess(
        job: WorkspaceJobEntity,
        workspace: WorkspaceEntity,
        process: RunningWorkspaceJob,
        generation: Long,
    ) {
        appScope.launch(Dispatchers.IO) {
            delay(job.maxRuntimeMs)
            if (running.containsKey(job.id)) {
                Log.w(TAG, "job ${job.id} exceeded max runtime, terminating")
                running[job.id]?.timedOut = true
                runCatching { process.terminate() }
            }
        }
        appScope.launch(Dispatchers.IO) {
            val exitCode = runCatching { runInterruptible { process.waitFor() } }.getOrDefault(-1)
            finishRunningJob(job.id, workspace, exitCode, timedOut = false, generation = generation)
        }
    }

    private fun watchPty(
        job: WorkspaceJobEntity,
        workspace: WorkspaceEntity,
        session: PtyJobSession,
        generation: Long,
    ) {
        appScope.launch(Dispatchers.IO) {
            // 既轮询屏幕快照, 也检测退出
            val deadline = System.currentTimeMillis() + job.maxRuntimeMs
            while (true) {
                session.poll()
                if (session.finished) break
                if (System.currentTimeMillis() > deadline) {
                    Log.w(TAG, "pty job ${job.id} exceeded max runtime, terminating")
                    running[job.id]?.timedOut = true
                    session.kill()
                    break
                }
                delay(PTY_POLL_INTERVAL_MS)
            }
            poll(session)
            finishRunningJob(job.id, workspace, session.exitStatus, timedOut = false, generation = generation)
        }
    }

    private suspend fun finishRunningJob(
        jobId: String,
        workspace: WorkspaceEntity,
        exitCode: Int,
        timedOut: Boolean,
        generation: Long,
    ) {
        // 这一代进程已被新一代顶替（同一 jobId 重跑）: 不要动新进程的行与句柄。
        if (running[jobId]?.generation?.let { it != generation } == true) return
        val entry = running.remove(jobId)
        val entryPid = entry?.pid?.takeIf { it > 0L }
        val killed = killRequested.remove(jobId)
        runCatching { entry?.stdout?.close() }
        runCatching { entry?.stderr?.close() }
        ptyJobs.remove(jobId)
        bumpRunning()

        val current = dao.getJob(jobId) ?: return
        if (current.status != WorkspaceJobStatus.RUNNING.name) return

        val finishedAt = System.currentTimeMillis()
        val status = when {
            killed -> WorkspaceJobStatus.KILLED
            timedOut || entry?.timedOut == true -> WorkspaceJobStatus.TIMED_OUT
            exitCode == 0 -> WorkspaceJobStatus.SUCCEEDED
            else -> WorkspaceJobStatus.FAILED
        }
        val updated = current.copy(
            status = status.name,
            exitCode = exitCode,
            // 保住启动时落库的 pid: 它是句柄丢失后唯一还能找到该进程的线索。
            pid = current.pid ?: entryPid,
            finishedAt = finishedAt,
            runtimeMs = finishedAt - (current.startedAt ?: finishedAt),
            logBytesOut = logStore.logicalSize(
                logStore.streamFile(workspace.root, jobId, JobLogStream.STDOUT)
            ),
            logBytesErr = logStore.logicalSize(
                logStore.streamFile(workspace.root, jobId, JobLogStream.STDERR)
            ),
            logTruncated = logStore.logicalSize(
                logStore.streamFile(workspace.root, jobId, JobLogStream.STDOUT)
            ) > JobLogStore.MAX_STREAM_BYTES,
            error = if (status == WorkspaceJobStatus.FAILED) "exit code $exitCode" else current.error,
        )
        dao.upsertJob(updated)
        emitFinished(updated)
    }

    private fun emitFinished(job: WorkspaceJobEntity) {
        eventBus.tryEmit(
            AppEvent.WorkspaceJobFinished(
                jobId = job.id,
                workspaceId = job.workspaceId,
                defId = job.defId,
                name = job.name,
                status = job.status,
                exitCode = job.exitCode,
                runtimeMs = job.runtimeMs,
                conversationId = job.conversationId,
                autoWake = job.autoWake,
                notify = job.notify,
            )
        )
    }

    private fun ensureKeepAlive() {
        appScope.launch {
            val enabled = runCatching { settingsStore.settingsFlowRaw.first().backgroundRunningEnabled }
                .getOrDefault(false)
            if (enabled) BackgroundKeepAliveService.start(context)
        }
    }

    // ---- 操作 ----

    suspend fun kill(jobId: String, force: Boolean = false): Boolean {
        val entry = running[jobId]
        if (entry == null) {
            // 没有在跑的内存句柄: 可能是进程已消失但 DB 还停在 RUNNING（App 进程被杀、
            // 监听协程中断等）。以前这里直接 return false, UI 上"停止"就变成点了没反应、
            // 行还永远停在"运行中"。这里改为把状态收敛掉, 让界面能自愈。
            val stale = runCatching { dao.getJob(jobId) }.getOrNull() ?: return false
            if (stale.status != WorkspaceJobStatus.RUNNING.name) return false
            // 进程可能还活着: App 重启只清空了内存句柄, PRoot 子树会存活下来。
            // 优先按落库的 pid 精确结束, 再把状态收敛掉。
            // 优先按 pid 精确结束; 老数据没有 pid 时退回清扫本进程遗留的 PRoot 启动器。
            val killedProcess = processControl.killByPid(stale.pid) || (processControl.reapOrphanLaunchers() > 0)
            val now = System.currentTimeMillis()
            val reconciled = stale.copy(
                status = if (killedProcess) WorkspaceJobStatus.KILLED.name
                else WorkspaceJobStatus.INTERRUPTED.name,
                error = if (killedProcess) null else (stale.error ?: "process lost (stopped from UI)"),
                finishedAt = now,
                runtimeMs = now - (stale.startedAt ?: now),
            )
            runCatching { dao.upsertJob(reconciled) }
                .onFailure { Log.w(TAG, "kill: reconcile stale job $jobId failed", it) }
            emitFinished(reconciled)
            Log.w(
                TAG,
                "kill: job $jobId had no in-memory handle (pid=" + stale.pid + "), " +
                    if (killedProcess) "killed the live process" else "no live process, marked INTERRUPTED",
            )
            return true
        }
        killRequested.add(jobId)
        entry.timedOut = false
        ptyJobs[jobId]?.kill()
        // 先按 pid 收(能连 PRoot 子树一起收干净), 再退回句柄 destroy —— PRoot 自身不一定把
        // 子进程带走, 只 destroy 句柄会留下还在跑的 bash/python。
        val pidKilled = processControl.killByPid(entry.pid)
        val handleOk = runCatching {
            entry.handle.terminate(if (force) 0L else RunningWorkspaceJob.TERMINATE_GRACE_MS)
            true
        }.getOrDefault(false)
        Log.i(TAG, "kill: job " + jobId + " pid=" + entry.pid + " pidKilled=" + pidKilled + " handleOk=" + handleOk)
        return pidKilled || handleOk
    }


    suspend fun getJob(jobId: String): WorkspaceJobEntity? = dao.getJob(jobId)

    fun jobsFlow(workspaceId: String, limit: Int = 50) = dao.listJobsFlow(workspaceId, limit)

    fun defsFlow(workspaceId: String) = dao.listDefsFlow(workspaceId)

    /** 所有 workspace 的任务（全局 jobs 页 / 侧边栏用）。 */
    fun recentJobsFlow(limit: Int = 200) = dao.listRecentJobsFlow(limit)

    fun runningJobsFlow() = dao.listRunningJobsFlow()

    /** 所有 workspace 的定时任务定义（全局任务页用）。 */
    fun recentDefsFlow() = dao.listAllDefsFlow()

    suspend fun listDefs(workspaceId: String) = dao.listDefs(workspaceId)

    suspend fun runDefNow(def: WorkspaceJobDefEntity): WorkspaceJobEntity =
        startFromDef(def, triggerSource = JobTriggerSource.AI, requireKeepAlive = false)

    suspend fun setDefEnabled(def: WorkspaceJobDefEntity, enabled: Boolean) {
        dao.upsertDef(def.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteDef(defId: String): Boolean = dao.deleteDef(defId) > 0

    suspend fun listJobs(workspaceId: String, limit: Int = 50): List<WorkspaceJobEntity> =
        dao.listJobs(workspaceId, limit)

    suspend fun removeJob(jobId: String): Boolean {
        val job = dao.getJob(jobId) ?: return false
        if (job.status == WorkspaceJobStatus.RUNNING.name) return false
        val workspace = workspaceDao.getById(job.workspaceId)
        if (workspace != null) logStore.delete(workspace.root, jobId)
        dao.deleteJob(jobId)
        return true
    }

    suspend fun restart(jobId: String, commandOverride: String? = null, cwdOverride: String? = null): WorkspaceJobEntity? {
        val job = dao.getJob(jobId) ?: return null
        val workspace = workspaceDao.getById(job.workspaceId) ?: return null
        // 已在跑的任务不允许再起一个: 旧进程的监听协程会用同一个 jobId 收尾,
        // 把新进程刚写好的 RUNNING 行覆盖成已结束, 界面就会出现"行是结束的、进程还在跑"。
        if (running.containsKey(jobId)) return null
        return launchProcess(
            job = job.copy(
                id = Uuid.random().toString(),
                command = commandOverride ?: job.command,
                cwd = cwdOverride ?: job.cwd,
                pid = null,
                createdAt = System.currentTimeMillis(),
                triggerSource = JobTriggerSource.RESTART.name,
                wakeState = JobWakeState.NONE.name,
            ),
            workspace = workspace,
            command = commandOverride ?: job.command,
            env = emptyMap(),
        )
    }

    /** 等待任务结束, 返回是否在超时前结束。 */
    suspend fun waitFor(jobId: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val job = dao.getJob(jobId) ?: return true
            if (WorkspaceJobStatus.from(job.status).isFinished) return true
            if (!running.containsKey(jobId) && job.status != WorkspaceJobStatus.RUNNING.name) return true
            delay(250)
        }
        return false
    }

    suspend fun sendInput(jobId: String, input: String, appendNewline: Boolean): Boolean {
        val session = ptyJobs[jobId] ?: return false
        session.send(input, appendNewline)
        return true
    }

    suspend fun sendKey(jobId: String, key: String): Boolean {
        val session = ptyJobs[jobId] ?: return false
        session.sendKey(key)
        return true
    }

    /** 读取日志（流式游标见 JobLogStore.readChunk）。 */
    suspend fun readLog(
        jobId: String,
        stream: JobLogStream,
        since: Long,
        maxBytes: Int,
    ): JobLogChunk? {
        val job = dao.getJob(jobId) ?: return null
        val workspace = workspaceDao.getById(job.workspaceId) ?: return null
        val file = logStore.streamFile(workspace.root, jobId, stream)
        return logStore.readChunk(file, since, maxBytes)
    }

    suspend fun screenOf(jobId: String): String? = ptyJobs[jobId]?.transcript

    fun isRunning(jobId: String): Boolean = running.containsKey(jobId)

    suspend fun clearFinished(workspaceId: String): Int {
        val jobs = dao.listJobs(workspaceId, 500).filter { WorkspaceJobStatus.from(it.status).isFinished }
        val workspace = workspaceDao.getById(workspaceId)
        jobs.forEach { job ->
            if (workspace != null) logStore.delete(workspace.root, job.id)
        }
        return dao.clearFinished(workspaceId)
    }

    val pollIntervalMs: Long get() = PTY_POLL_INTERVAL_MS

    private suspend fun poll(session: PtyJobSession) {
        runCatching { session.poll() }
    }
}
