package me.rerere.rikkahub.data.job

import me.rerere.rikkahub.data.run.ActiveRun
import me.rerere.rikkahub.data.run.RunExecutor
import me.rerere.rikkahub.data.run.RunHandle
import me.rerere.rikkahub.data.run.RunKind
import me.rerere.rikkahub.data.run.RunLaunchResult
import me.rerere.rikkahub.data.run.RunRecord
import me.rerere.rikkahub.data.run.RunRegistry
import me.rerere.rikkahub.data.run.RunStatus

/**
 * shell 后台任务的执行体。
 *
 * 它把"跑一条命令"这件事接到编排层上, 于是 shell 任务与子代理**平级**:
 * 同一套配额分账、同一套看门狗、同一套取消收尾。
 *
 * 真正的进程处理仍由 [WorkspaceJobManager] 完成（它是这套东西多年调出来的实现,
 * 尤其是 pid 定位与进程树结束, 见 [ShellProcessControl]）; 本类只负责把它接进编排层,
 * 并在编排层要求停止时用"句柄 + 进程树兜底"两条路一起收干净。
 */
internal class ShellRunExecutor(
    private val manager: WorkspaceJobManager,
) : RunExecutor {

    override val kind: RunKind = RunKind.JOB

    override suspend fun launch(active: ActiveRun, registry: RunRegistry): RunLaunchResult {
        val started = runCatching { manager.launchFromOrchestrator(active.record, skipQuotaCheck = true) }
            .getOrElse { return RunLaunchResult.NotStarted(it.message ?: "failed to start process") }
        val handle = manager.handleFor(started.id)
            ?: return RunLaunchResult.NotStarted("process vanished right after start")
        active.handle = handle
        return RunLaunchResult.Started(handle)
    }

    override fun isAlive(runId: String): Boolean = manager.isRunning(runId)

    override suspend fun onFinish(active: ActiveRun, status: RunStatus, exitCode: Int?, error: String?) {
        // 直接把"这一代已经结束"告诉 Manager: 它据此清掉内存句柄（并保持进程收尾语义不变）。
        manager.onOrchestratedRunFinished(active.record.runId, status)
    }

    /**
     * 启动清理: 先真正收掉遗留进程（这里才认识 pid 与 /proc）, 再把记录收敛为 INTERRUPTED。
     *
     * 顺序不能反 —— 反了就会"界面显示已中断、后台还在跑"。
     */
    override suspend fun reconcileRegistry(registry: RunRegistry): Int {
        manager.killLeftoverProcesses()
        return registry.reconcile()
    }
}
