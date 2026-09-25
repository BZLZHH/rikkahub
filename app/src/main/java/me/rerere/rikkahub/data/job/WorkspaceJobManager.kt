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

/** PRoot 启动器的可执行文件名: 出现在 cmdline 里即可认出这是本应用拉起的沙箱进程。 */
private const val PROOT_LAUNCHER_MARKER = "libproot_exec"

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
        /** 这一代进程的唯一标识: 同一 jobId 被重跑后, 旧进程收尾时不能覆盖新进程的行。 */
        val generation: Long,
        /** 真实子进程 pid(取不到为 -1): 内存句柄丢失时用它兜底结束进程。 */
        val pid: Long,
        var timedOut: Boolean = false,
    )

    private val logStore = JobLogStore(File(context.filesDir, "workspace-jobs"))
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
                val killedProcess = fallbackKillByPid(stale.pid)
                if (killedProcess) Log.w(TAG, "reconcileOnStart: killed leftover process tree for " + stale.id)
            }
        }.onFailure { Log.e(TAG, "reconcileOnStart: killing leftovers failed", it) }
        runCatching {
            val reaped = reapOrphanProotLaunchers()
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
        checkQuota(workspaceId)

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

        val pid = resolveLauncherPidWithRetry(process.pid)
        Log.i(TAG, "job " + job.id + " launched, process.pid=" + process.pid + " resolvedPid=" + pid)
        diagLog("launch job=" + job.id + " process.pid=" + process.pid + " resolvedPid=" + pid)
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
            val killedProcess = fallbackKillByPid(stale.pid) || (reapOrphanProotLaunchers() > 0)
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
        val pidKilled = fallbackKillByPid(entry.pid)
        val handleOk = runCatching {
            entry.handle.terminate(if (force) 0L else RunningWorkspaceJob.TERMINATE_GRACE_MS)
            true
        }.getOrDefault(false)
        Log.i(TAG, "kill: job " + jobId + " pid=" + entry.pid + " pidKilled=" + pidKilled + " handleOk=" + handleOk)
        return pidKilled || handleOk
    }

    /**
     * 内存句柄丢失后, 按落库的 pid 结束进程。
     *
     * 只杀直接子进程不够: 真正的活儿在 PRoot 子树里（proot -> bash -> python3）。PRoot 以
     * --kill-on-exit 启动, 正常路径下由它负责清子树; 但 App 被系统杀掉时它没机会执行,
     * 子树会变成孤儿继续跑。这里先收子孙, 再收自己。
     */
    private fun fallbackKillByPid(pid: Long?): Boolean {
        if (pid == null || pid <= 0L) return false
        val target = pid.toInt()
        var killedAny = false
        descendantsOf(target).forEach { child ->
            if (!isOwnProcess(child)) return@forEach
            android.os.Process.sendSignal(child, android.os.Process.SIGNAL_KILL)
            killedAny = true
        }
        // pid 可能已被系统复用给别的进程, 所以只杀确实属于本应用的进程。
        if (isOwnProcess(target)) {
            android.os.Process.sendSignal(target, android.os.Process.SIGNAL_KILL)
            killedAny = true
        }
        return killedAny
    }

    /**
     * 收掉由本进程直接启动、却已经没有对应 job 的 PRoot 启动器。
     *
     * 老版本没有把 pid 落库, 只靠 pid 收不掉这些遗留进程; 但 PRoot 启动器一定是 App 主进程的
     * 直接子进程, 所以按父 pid 就能安全识别(不会误伤其它应用的进程)。它带着 --kill-on-exit,
     * 收掉这一层就会连带清掉自己的 bash/python 子树。
     */
    private fun reapOrphanProotLaunchers(): Int {
        val myPid = android.os.Process.myPid()
        var reaped = 0
        java.io.File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == myPid) return@forEach
            val cmdline = runCatching {
                java.io.File(entry, "cmdline").readBytes().toString(Charsets.UTF_8)
            }.getOrNull() ?: return@forEach
            if (!cmdline.contains(PROOT_LAUNCHER_MARKER)) return@forEach
            val stat = runCatching { java.io.File(entry, "stat").readText() }.getOrNull() ?: return@forEach
            val ppid = stat.substringAfterLast(')').trimStart().split(' ').getOrNull(1)?.toIntOrNull()
            if (ppid != myPid) return@forEach
            if (!isOwnProcess(pid)) return@forEach
            descendantsOf(pid).forEach { child ->
                if (isOwnProcess(child)) android.os.Process.sendSignal(child, android.os.Process.SIGNAL_KILL)
            }
            android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL)
            Log.w(TAG, "reaped orphan proot launcher pid=" + pid)
            reaped++
        }
        return reaped
    }

    /**
     * 解析并重试: PRoot 子进程可能在 startJob 返回后才被 fork 出来, 扫一次常常扫不到,
     * 所以隔一点时间多扫几次, 尽量把 pid 落到库里(它是 App 重启后唯一还能停掉任务的线索)。
     */
    private fun resolveLauncherPidWithRetry(fromProcess: Long): Long {
        if (fromProcess > 0L) return fromProcess
        repeat(5) { attempt ->
            val found = findLauncherChildPid()
            if (found != null) return found.toLong()
            runCatching { Thread.sleep(60L * (attempt + 1)) }
        }
        return -1L
    }

    /**
     * 取这个任务的 PRoot 启动器 pid。
     *
     * android 的 java.lang.Process.pid() 是隐藏 API, 在 Android 16 上反射常拿到 -1, 所以
     * 拿不到时退回扫描 /proc: 由本进程直接拉起的 libproot_exec 子进程就是它。
     */
    private fun resolveLauncherPid(fromProcess: Long): Long {
        if (fromProcess > 0L) return fromProcess
        return findLauncherChildPid()?.toLong() ?: -1L
    }

    /** 扫描 /proc, 找出父进程是自己、且 cmdline 是本应用 PRoot 启动器的那个子进程。 */
    private fun findLauncherChildPid(): Int? {
        val myPid = android.os.Process.myPid()
        var scanned = 0
        var matchedMarker = 0
        var matchedPpid = 0
        var ownedOk = 0
        java.io.File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == myPid) return@forEach
            val cmdline = runCatching {
                java.io.File(entry, "cmdline").readBytes().toString(Charsets.UTF_8)
            }.getOrNull() ?: return@forEach
            scanned++
            if (!cmdline.contains(PROOT_LAUNCHER_MARKER)) return@forEach
            matchedMarker++
            val stat = runCatching { java.io.File(entry, "stat").readText() }.getOrNull() ?: return@forEach
            val ppid = stat.substringAfterLast(')').trimStart().split(' ').getOrNull(1)?.toIntOrNull()
            if (ppid == myPid) {
                matchedPpid++
                if (isOwnProcess(pid)) {
                    ownedOk++
                    diagLog("findLauncherChildPid: FOUND pid=" + pid + " scanned=" + scanned)
                    return pid
                }
            }
        }
        diagLog(
            "findLauncherChildPid: NOT FOUND myPid=" + myPid + " scanned=" + scanned +
                " marker=" + matchedMarker + " ppidMatch=" + matchedPpid + " ownOk=" + ownedOk
        )
        return null
    }

    /** 诊断: 把 pid 解析过程写到文件里(MIUI 上 logcat 常吞掉应用日志, 只能落盘排查)。 */
    private fun diagLog(msg: String) {
        runCatching {
            val f = java.io.File(context.filesDir, "job-pid-debug.log")
            if (f.length() > 256 * 1024) f.delete()
            f.appendText(System.currentTimeMillis().toString() + " " + msg + "\n")
        }
    }

    /** 该 pid 是否属于本应用, 防止 pid 被系统复用后误杀别人的进程。
     *
     * 从 /proc/<pid>/status 的 Uid: 行读取 —— 注意不能用 java.nio.file.Files.getAttribute("unix:uid"),
     * Android 的 NIO 实现不支持该属性, 会静默返回 null, 导致所有结束操作都被跳过。
     */
    private fun isOwnProcess(pid: Int): Boolean {
        val myUid = android.os.Process.myUid()
        val status = runCatching {
            java.io.File("/proc/" + pid + "/status").readText()
        }.getOrNull()
        if (status == null) {
            // 读不到就放行: 调用点已确认它是"我们自己的 PRoot 启动器的子进程",
            // 这个归属关系比 uid 更强; 反过来一旦这里读失败就全部拒绝, 会连真正的
            // 目标进程都停不掉(表现为"点了停止没反应")。
            diagLog("isOwnProcess pid=" + pid + " status UNREADABLE, allowing")
            return true
        }
        val uidLine = status.lineSequence().firstOrNull { it.startsWith("Uid:") }
        val uid = uidLine?.removePrefix("Uid:")?.trim()?.split(' ')?.firstOrNull()?.toIntOrNull()
        if (uid == null) {
            diagLog("isOwnProcess pid=" + pid + " no Uid line, allowing")
            return true
        }
        if (uid != myUid) {
            diagLog("isOwnProcess pid=" + pid + " uid=" + uid + " myUid=" + myUid + " -> reject")
        }
        return uid == myUid
    }

    /** 遍历 /proc 找出 [pid] 的所有后代进程, 深度优先(先子后父的顺序返回)。 */
    private fun descendantsOf(pid: Int): List<Int> {
        val children = HashMap<Int, MutableList<Int>>()
        java.io.File("/proc").listFiles()?.forEach { entry ->
            val childPid = entry.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { java.io.File(entry, "stat").readText() }.getOrNull() ?: return@forEach
            // 格式: pid (comm) state ppid ...  comm 可能含空格与括号, 取最后一个 ')' 之后解析。
            val afterComm = stat.substringAfterLast(')').trimStart()
            val ppid = afterComm.split(' ').getOrNull(1)?.toIntOrNull() ?: return@forEach
            children.getOrPut(ppid) { mutableListOf() }.add(childPid)
        }
        val result = mutableListOf<Int>()
        fun walk(current: Int) {
            children[current]?.forEach { child ->
                walk(child)
                result.add(child)
            }
        }
        walk(pid)
        return result
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
