package me.rerere.rikkahub.data.run

/**
 * 耐久账本: 把 [RunRecord] 落到某种存储（各自的状态表）并读回。
 *
 * 编排层只依赖这个接口, 因此不需要知道有几个表、表里还有什么专用列。
 * 另外它还负责"启动时的遗留清理": 每种执行体知道自己的遗留该怎么收
 * （shell 任务要按 pid 杀进程树, 子代理只需把协程状态标记掉）。
 */
interface RunRegistry {
    val kind: RunKind

    /** 登记一次新的执行（状态 PENDING/RUNNING）。 */
    suspend fun register(record: RunRecord)

    /** 更新状态/结束时间/错误。 */
    suspend fun updateStatus(
        runId: String,
        status: RunStatus,
        finishedAt: Long? = null,
        error: String? = null,
    )

    /** 列出所有"账上还在跑"的 run（App 被杀后用来收敛）。 */
    suspend fun listUnfinished(): List<RunRecord>

    /**
     * 启动时清理遗留。
     *
     * 默认实现只把未结束的记录标记为 [RunStatus.INTERRUPTED];
     * 有进程残留的执行体（shell 任务）需要覆写, 先真正把进程收掉再标记。
     */
    suspend fun reconcile(): Int {
        var count = 0
        val now = System.currentTimeMillis()
        listUnfinished().forEach { record ->
            updateStatus(
                runId = record.runId,
                status = RunStatus.INTERRUPTED,
                finishedAt = now,
                error = "process lost (app killed)",
            )
            count++
        }
        return count
    }
}
