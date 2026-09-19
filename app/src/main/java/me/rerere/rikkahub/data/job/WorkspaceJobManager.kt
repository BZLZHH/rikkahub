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
) {
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
        var timedOut: Boolean = false,
    )

    private val logStore = JobLogStore(File(context.filesDir, "workspace-jobs"))
    private val running = ConcurrentHashMap<String, RunningEntry>()
    private val ptyJobs = ConcurrentHashMap<String, PtyJobSession>()
    private val killRequested = ConcurrentHashMap.newKeySet<String>()

    private val _runningCount = MutableStateFlow(0)

    /** 当前运行中的任务数（UI 用）。 */
    val runningCount: StateFlow<Int> = _runningCount.asStateFlow()

    fun logs(workspaceRoot: String): JobLogStore = logStore

    // ---- 生命周期 ----

    /** App 启动时调用: 上一进程遗留的 RUNNING 记录必然已经死亡, 标记为 interrupted; 顺带补跑 deferred。 */
    suspend fun reconcileOnStart() {
        val now = System.currentTimeMillis()
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
            if (countRunning() >= MAX_CONCURRENT_GLOBAL) return@forEach
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
        checkQuota(workspaceId)

        val now = System.currentTimeMillis()
        val created = WorkspaceJobEntity(
            id = Uuid.random().toString(),
            workspaceId = workspaceId,
            name = name?.takeIf { it.isNotBlank() } ?: deriveName(command),
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
        return launchProcess(created, workspace, command, env)
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

        checkQuota(def.workspaceId)
        val resolved = JobParamResolver.resolve(def.command, def.params().toParamDefs(), args)
        val require = resolved.missing
        require(require.isEmpty()) { "Missing required params: ${require.joinToString(", ")}" }

        val now = System.currentTimeMillis()
        val created = WorkspaceJobEntity(
            id = Uuid.random().toString(),
            workspaceId = def.workspaceId,
            defId = def.id,
            name = def.name,
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
        return launchProcess(created, workspace, resolved.command, def.env() + resolved.env)
    }

    private fun deriveName(command: String): String =
        command.lineSequence().firstOrNull()?.trim()?.take(60)?.takeIf { it.isNotEmpty() } ?: "job"

    private fun checkQuota(workspaceId: String) {
        val global = countRunning()
        require(global < MAX_CONCURRENT_GLOBAL) {
            "quota_exceeded: ${MAX_CONCURRENT_GLOBAL} jobs already running (use job_kill or job_wait)"
        }
        val inWorkspace = running.values.count { running ->
            workspaceIdOf(running.workspaceRoot) == workspaceId
        }
        require(inWorkspace < MAX_CONCURRENT_PER_WORKSPACE) {
            "quota_exceeded: ${MAX_CONCURRENT_PER_WORKSPACE} jobs already running in this workspace"
        }
    }

    private fun workspaceIdOf(root: String): String? =
        workspaceManager.workspaceDir(root).name

    private fun countRunning(): Int = running.size

    private fun bumpRunning() {
        _runningCount.value = running.size
    }

    // ---- 执行 ----

    private suspend fun launchProcess(
        job: WorkspaceJobEntity,
        workspace: WorkspaceEntity,
        command: String,
        env: Map<String, String>,
    ): WorkspaceJobEntity {
        val startedAt = System.currentTimeMillis()
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
            startPtyJob(started, workspace, command, env)
        } else {
            startPipeJob(started, workspace, command, env)
        }
    }

    private suspend fun startPipeJob(
        job: WorkspaceJobEntity,
        workspace: WorkspaceEntity,
        command: String,
        env: Map<String, String>,
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

        running[job.id] = RunningEntry(
            workspaceRoot = workspace.root,
            handle = JobHandle { grace -> process.terminate(grace) },
            stdout = outStream,
            stderr = errStream,
        )
        bumpRunning()
        watchProcess(job, workspace, process)
        return dao.getJob(job.id) ?: job
    }

    private suspend fun startPtyJob(
        job: WorkspaceJobEntity,
        workspace: WorkspaceEntity,
        command: String,
        env: Map<String, String>,
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
        )
        bumpRunning()
        watchPty(job, workspace, session)
        return dao.getJob(job.id) ?: job
    }

    private fun watchProcess(
        job: WorkspaceJobEntity,
        workspace: WorkspaceEntity,
        process: RunningWorkspaceJob,
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
            finishRunningJob(job.id, workspace, exitCode, timedOut = false)
        }
    }

    private fun watchPty(
        job: WorkspaceJobEntity,
        workspace: WorkspaceEntity,
        session: PtyJobSession,
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
            finishRunningJob(job.id, workspace, session.exitStatus, timedOut = false)
        }
    }

    private suspend fun finishRunningJob(
        jobId: String,
        workspace: WorkspaceEntity,
        exitCode: Int,
        timedOut: Boolean,
    ) {
        val entry = running.remove(jobId)
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
        val entry = running[jobId] ?: return false
        killRequested.add(jobId)
        entry.timedOut = false
        ptyJobs[jobId]?.kill()
        return runCatching {
            entry.handle.terminate(if (force) 0L else RunningWorkspaceJob.TERMINATE_GRACE_MS)
            true
        }.getOrDefault(false)
    }

    suspend fun getJob(jobId: String): WorkspaceJobEntity? = dao.getJob(jobId)

    fun jobsFlow(workspaceId: String, limit: Int = 50) = dao.listJobsFlow(workspaceId, limit)

    fun defsFlow(workspaceId: String) = dao.listDefsFlow(workspaceId)

    /** 所有 workspace 的任务（全局 jobs 页 / 侧边栏用）。 */
    fun recentJobsFlow(limit: Int = 200) = dao.listRecentJobsFlow(limit)

    fun runningJobsFlow() = dao.listRunningJobsFlow()

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
