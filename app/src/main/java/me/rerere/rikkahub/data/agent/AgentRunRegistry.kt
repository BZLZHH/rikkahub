package me.rerere.rikkahub.data.agent

import me.rerere.rikkahub.data.db.dao.AgentRunDAO
import me.rerere.rikkahub.data.db.entity.AgentRunEntity
import me.rerere.rikkahub.data.run.RunKind
import me.rerere.rikkahub.data.run.RunRecord
import me.rerere.rikkahub.data.run.RunRegistry
import me.rerere.rikkahub.data.run.RunStatus

/**
 * 子代理的耐久账本: [RunRecord] ⇄ `agent_runs`。
 *
 * 与 [me.rerere.rikkahub.data.job.ShellRunRegistry] 结构对称 —— 这是"两种执行体平级"的直接体现:
 * 各自一张表, 但都向编排层投影成同一个 [RunRecord]。
 */
class AgentRunRegistry(
    private val dao: AgentRunDAO,
) : RunRegistry {

    override val kind: RunKind = RunKind.AGENT

    override suspend fun register(record: RunRecord) {
        val existing = dao.getById(record.runId)
        if (existing != null) {
            dao.upsert(
                existing.copy(
                    status = record.status.name,
                    startedAt = record.startedAt ?: existing.startedAt,
                )
            )
            return
        }
        val now = System.currentTimeMillis()
        dao.upsert(
            AgentRunEntity(
                id = record.runId,
                workspaceId = record.workspaceId,
                conversationId = record.conversationId,
                assistantId = record.assistantId,
                title = record.title?.takeIf { it.isNotBlank() } ?: record.runId,
                prompt = "",
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
        val run = dao.getById(runId) ?: return
        if (run.status == status.name && (finishedAt == null || run.finishedAt != null)) return
        val finished = finishedAt ?: run.finishedAt
        dao.upsert(
            run.copy(
                status = status.name,
                finishedAt = finished,
                runtimeMs = if (finished != null && run.startedAt != null) {
                    finished - run.startedAt
                } else {
                    run.runtimeMs
                },
                error = if (status == RunStatus.SUCCEEDED) null else (error ?: run.error),
            )
        )
    }

    override suspend fun listUnfinished(): List<RunRecord> =
        dao.listRunning().map { it.toRunRecord() }

    override suspend fun reconcile(): Int {
        val pending = dao.listRunning()
        if (pending.isEmpty()) return 0
        // 子代理没有进程需要收尾: 协程随 App 进程一起没了, 只需把账收敛掉。
        dao.markRunningAs(
            status = RunStatus.INTERRUPTED.name,
            finishedAt = System.currentTimeMillis(),
            error = "agent lost (app killed)",
        )
        return pending.size
    }
}

/** `agent_runs` → 公共投影。 */
internal fun AgentRunEntity.toRunRecord(): RunRecord = RunRecord(
    runId = id,
    kind = RunKind.AGENT,
    workspaceId = workspaceId,
    title = title,
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
