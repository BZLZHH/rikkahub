package me.rerere.rikkahub.data.run

/**
 * 一次后台执行的通用状态。
 *
 * 对 shell 后台任务与子代理都成立 —— 所以它不属于 `job` 包。
 * 旧名 [me.rerere.rikkahub.data.job.WorkspaceJobStatus] 是同类型的 typealias,
 * 既有调用点不需要改动。
 */
enum class RunStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    KILLED,
    TIMED_OUT,
    INTERRUPTED,

    /** 定时触发时后台无法保证常驻, 延后补跑（仅 shell 任务会用到） */
    DEFERRED;

    val isFinished: Boolean
        get() = this != PENDING && this != RUNNING && this != DEFERRED

    companion object {
        fun from(value: String?): RunStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PENDING
    }
}
