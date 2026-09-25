package me.rerere.rikkahub.data.run

/** 启动结果: 只有"起不来"才算失败; 起来了之后跑失败是 run 状态的事。 */
sealed interface RunLaunchResult {
    /** 已开始执行。 */
    data class Started(val handle: RunHandle) : RunLaunchResult

    /** 压根没起来（配额之外的原因, 如 workspace 不存在、rootfs 缺失、进程创建异常）。 */
    data class NotStarted(val error: String) : RunLaunchResult
}

/**
 * 一种可被编排的执行体。
 *
 * 新增执行体（子代理、将来的 HTTP 步骤 / 内嵌 JS 步骤）只需实现本接口并注册到
 * [RunOrchestrator]，配额、看门狗、取消收尾、通知与唤醒都由编排层统一提供。
 *
 * 实现者**必须**自己处理"如何终止"与"如何知道自己结束了", 因为这两件事只有它懂。
 */
interface RunExecutor {
    val kind: RunKind

    /**
     * 检查配额之外的前置条件并启动执行体。
     *
     * 实现者需要:
     * - 把 [active] 的 handle 挂上（`active.handle = ...`）并返回 [RunLaunchResult.Started];
     * - 需要能取消自己内部协程时, 设置 `active.cancel`（编排层会调它）;
     * - 不要在这里改 [RunStatus]（编排层统一负责）;
     * - 不要自己管超时（编排层统一负责）。
     */
    suspend fun launch(active: ActiveRun, registry: RunRegistry): RunLaunchResult

    /** 句柄丢失时判断是否还在跑（App 重启后用于收敛）。 */
    fun isAlive(runId: String): Boolean = false

    /** 结束后写入本执行体特有的结果字段（如 shell 的 exit_code 与日志大小）。 */
    suspend fun onFinish(active: ActiveRun, status: RunStatus, exitCode: Int?, error: String?) {}

    /** 启动时的遗留清理; 返回清理条数。 */
    suspend fun reconcileRegistry(registry: RunRegistry): Int = registry.reconcile()
}
