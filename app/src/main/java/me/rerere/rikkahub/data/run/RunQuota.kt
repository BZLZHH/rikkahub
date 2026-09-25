package me.rerere.rikkahub.data.run

/**
 * 后台执行的并发配额。
 *
 * 由用户在设置里调整（见 Settings.maxConcurrent*）。shell 后台任务与子代理**分账**：
 * 一条长跑的 shell 任务不应该把子代理饿死，反之同理。
 *
 * 上限存在的意义是保护设备：并发过高会让 PRoot 沙箱、模型请求与前台服务一起抖动。
 */
object RunQuota {
    const val DEFAULT_MAX_CONCURRENT_JOBS = 4
    const val DEFAULT_MAX_CONCURRENT_JOBS_PER_WORKSPACE = 2
    const val DEFAULT_MAX_CONCURRENT_AGENTS = 2
    const val DEFAULT_MAX_CONCURRENT_AGENTS_PER_WORKSPACE = 2

    /** 硬上限: 超过它设备会明显卡顿, 所以即使手填也不放行。 */
    const val MAX_CONCURRENT_LIMIT = 16
    const val MAX_CONCURRENT_PER_WORKSPACE_LIMIT = 8

    fun clampGlobal(value: Int): Int = value.coerceIn(1, MAX_CONCURRENT_LIMIT)

    fun clampPerWorkspace(value: Int): Int = value.coerceIn(1, MAX_CONCURRENT_PER_WORKSPACE_LIMIT)
}
