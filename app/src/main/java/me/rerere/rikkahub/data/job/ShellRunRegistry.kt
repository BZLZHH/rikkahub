package me.rerere.rikkahub.data.job

import me.rerere.rikkahub.data.db.dao.WorkspaceJobDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceJobEntity
import me.rerere.rikkahub.data.run.RunKind
import me.rerere.rikkahub.data.run.RunRecord
import me.rerere.rikkahub.data.run.RunRegistry
import me.rerere.rikkahub.data.run.RunStatus

/**
 * shell 后台任务的耐久账本: [RunRecord] ⇄ `workspace_jobs`。
 *
 * job 独有的列（command / cwd / mode / pid / exit_code / 日志大小 …）留在实体里,
 * 不进入 [RunRecord]; 这里只负责两者之间的投影。
 */
class ShellRunRegistry(
    private val dao: WorkspaceJobDAO,
) : RunRegistry {

    override val kind: RunKind = RunKind.JOB

    override suspend fun register(record: RunRecord) {
        val existing = dao.getJob(record.runId)
        if (existing != null) {
            dao.upsertJob(
                existing.copy(
                    status = record.status.name,
                    startedAt = record.startedAt ?: existing.startedAt,
                )
            )
            return
        }
        val now = System.currentTimeMillis()
        dao.upsertJob(
            WorkspaceJobEntity(
                id = record.runId,
                workspaceId = record.workspaceId,
                assistantId = record.assistantId,
                conversationId = record.conversationId,
                name = record.title?.takeIf { it.isNotBlank() } ?: record.runId,
                reason = record.title?.takeIf { it.isNotBlank() },
                command = "",
                status = record.status.name,
                triggerSource = record.triggerSource ?: "AI",
                createdAt = now,
                startedAt = record.startedAt,
                notify = record.notify,
                autoWake = record.autoWake,
                maxRuntimeMs = record.maxRuntimeMs,
            )
        )
    }

    override suspend fun updateStatus(
        runId: String,
        status: RunStatus,
        finishedAt: Long?,
        error: String?,
    ) {
        val job = dao.getJob(runId) ?: return
        if (job.status == status.name && (finishedAt == null || job.finishedAt != null)) return
        val finished = finishedAt ?: job.finishedAt
        dao.upsertJob(
            job.copy(
                status = status.name,
                finishedAt = finished,
                runtimeMs = if (finished != null && job.startedAt != null) {
                    finished - job.startedAt
                } else {
                    job.runtimeMs
                },
                // 成功时清掉历史错误, 其余情况保留原因（无新错误则沿用旧的）
                error = if (status == RunStatus.SUCCEEDED) null else (error ?: job.error),
            )
        )
    }

    override suspend fun listUnfinished(): List<RunRecord> =
        dao.listRunningJobs().map { it.toRunRecord() }

    override suspend fun reconcile(): Int {
        val pending = dao.listRunningJobs()
        if (pending.isEmpty()) return 0
        val now = System.currentTimeMillis()
        dao.markRunningAs(
            status = RunStatus.INTERRUPTED.name,
            exitCode = null,
            error = "process lost (app killed)",
            finishedAt = now,
        )
        return pending.size
    }
}

/** `workspace_jobs` → 公共投影。 */
internal fun WorkspaceJobEntity.toRunRecord(): RunRecord = RunRecord(
    runId = id,
    kind = RunKind.JOB,
    workspaceId = workspaceId,
    title = reason?.takeIf { it.isNotBlank() } ?: name,
    status = RunStatus.from(status),
    maxRuntimeMs = maxRuntimeMs,
    startedAt = startedAt,
    finishedAt = finishedAt,
    error = error,
    conversationId = conversationId,
    assistantId = assistantId,
    notify = notify,
    autoWake = autoWake,
    triggerSource = triggerSource,
)
