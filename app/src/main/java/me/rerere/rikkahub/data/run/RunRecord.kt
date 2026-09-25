package me.rerere.rikkahub.data.run

/**
 * 编排层眼里的"一次执行"的账目。
 *
 * 这是 job 的 `workspace_jobs` 与子代理的 `agent_runs` 的公共投影:
 * 两种执行体各自有表（各自的专用列差异很大）, 但对外共享这一份形状。
 *
 * 注意这里**没有** exit_code / pid / command: 那些是 shell 任务独有的概念,
 * 由 `WorkspaceJobEntity` 自己保留, 不进公共投影。
 */
data class RunRecord(
    val runId: String,
    val kind: RunKind,
    val workspaceId: String,
    /** 展示标题: 用户语言的一句话（shell 任务用 reason, 子代理用 title） */
    val title: String?,
    val status: RunStatus,
    /** 整个 run 的硬顶; 到点由编排层终止 */
    val maxRuntimeMs: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val error: String? = null,
    val conversationId: String? = null,
    val assistantId: String? = null,
    /** 结束后是否发通知 */
    val notify: Boolean = true,
    /** 结束后是否唤醒会话 */
    val autoWake: Boolean = false,
    /** 触发来源（AI / 用户 / 定时 / 重跑） */
    val triggerSource: String? = null,
) {
    val isFinished: Boolean get() = status.isFinished
}
