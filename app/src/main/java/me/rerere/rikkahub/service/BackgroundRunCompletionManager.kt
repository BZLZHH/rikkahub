package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.WORKSPACE_JOB_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.run.RunStatus
import me.rerere.rikkahub.utils.sendNotification
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

private const val TAG = "BackgroundRunNotify"

/** 短时间内多个运行结束时合并成一条唤醒, 免得刷屏、也免得连着烧几轮 token。 */
private const val WAKE_COALESCE_MS = 3_000L

/** 通知 id 段。job 那边用 3000+/3100, 这里错开。 */
private const val NOTIFICATION_ID_BASE = 3200

/**
 * 子代理 / workflow 结束后的**通知**与**唤醒会话**。
 *
 * 为什么需要它（这是补上的一个真窟窿）: `subagent_start` 一直带 `notify` / `auto_wake` 两个参数,
 * 它们也一路传进了数据库 —— 但 `RunOrchestrator` 的 `onRunFinished` 回调**从来没有被接上**,
 * 于是这两个开关**从来没有任何东西消费过**。AI 以为自己开了通知, 其实不会响。
 * workflow 那边更彻底: 连字段都没有。
 *
 * 与 [WorkspaceJobNotificationManager] / `JobWakeCoordinator` 结构对称, 但刻意分成两个类:
 * shell 任务那条链路是跑得好好的, 不该为了"顺手复用"去改它。
 */
class BackgroundRunCompletionManager(
    private val context: Application,
    private val appScope: AppScope,
    eventBus: AppEventBus,
) : KoinComponent {

    /** ChatService 用延迟注入, 免得 App 一启动就把整个聊天栈拉起来（与 JobWakeCoordinator 同一考虑）。 */
    private val chatService: ChatService by inject()

    private val pending = mutableListOf<AppEvent.BackgroundRunFinished>()

    @Volatile
    private var flushJob: Job? = null

    init {
        appScope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                if (event !is AppEvent.BackgroundRunFinished) return@collect
                if (event.notify) {
                    runCatching { notifyFinished(event) }
                        .onFailure { Log.e(TAG, "notify " + event.runId + " failed", it) }
                }
                if (event.autoWake && !event.conversationId.isNullOrBlank()) {
                    synchronized(pending) { pending += event }
                    scheduleFlush()
                }
            }
        }
    }

    private fun notifyFinished(event: AppEvent.BackgroundRunFinished) {
        val status = RunStatus.from(event.status)
        val prefix = when (status) {
            RunStatus.SUCCEEDED -> "完成"
            RunStatus.KILLED -> "已停止"
            RunStatus.TIMED_OUT -> "超时"
            RunStatus.INTERRUPTED -> "已中断"
            RunStatus.FAILED -> "失败"
            else -> "已结束"
        }
        val content = buildString {
            append(if (event.isWorkflow) "workflow" else "子代理")
            append(" · ").append(prefix)
            event.runtimeMs?.let { append(" · ").append(formatDuration(it)) }
        }
        context.sendNotification(
            channelId = WORKSPACE_JOB_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId(event.runId),
        ) {
            title = event.title.ifBlank { if (event.isWorkflow) "workflow" else "子代理" }
            this.content = content
            autoCancel = true
            category = if (status == RunStatus.SUCCEEDED) {
                NotificationCompat.CATEGORY_STATUS
            } else {
                NotificationCompat.CATEGORY_ERROR
            }
            contentIntent = openAppIntent(notificationId(event.runId))
            useBigTextStyle = true
        }
    }

    private fun scheduleFlush() {
        if (flushJob != null) return
        flushJob = appScope.launch(Dispatchers.Default) {
            delay(WAKE_COALESCE_MS)
            val batch = synchronized(pending) {
                val copy = pending.toList()
                pending.clear()
                copy
            }
            flushJob = null
            if (batch.isNotEmpty()) {
                runCatching { wake(batch) }.onFailure { Log.e(TAG, "auto wake failed", it) }
            }
        }
    }

    private suspend fun wake(batch: List<AppEvent.BackgroundRunFinished>) {
        batch.groupBy { it.conversationId }.forEach { (conversationId, runs) ->
            val uuid = conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: return@forEach
            val text = buildString {
                append(if (runs.size == 1 && runs.first().isWorkflow) "[workflow] " else "[后台执行] ")
                if (runs.size == 1) {
                    append(describe(runs.first()))
                } else {
                    append(runs.size).append(" 个执行已结束:\n")
                    runs.forEach { append("- ").append(describe(it)).append('\n') }
                }
                append("\n结果可以用 workflow_status 查看; AI 派出去的子代理用 subagent_result。")
            }
            chatService.sendMessage(
                conversationId = uuid,
                content = listOf(UIMessagePart.Text(text)),
                answer = true,
            )
        }
    }

    private fun describe(event: AppEvent.BackgroundRunFinished): String {
        val kind = if (event.isWorkflow) "workflow" else "子代理"
        val duration = event.runtimeMs?.let { " · " + formatDuration(it) }.orEmpty()
        return "'" + event.title + "' (" + kind + ") " + statusLabel(event.status) + duration
    }

    private fun statusLabel(status: String): String = when (RunStatus.from(status)) {
        RunStatus.SUCCEEDED -> "已完成"
        RunStatus.FAILED -> "失败了"
        RunStatus.KILLED -> "已被停止"
        RunStatus.TIMED_OUT -> "超时"
        RunStatus.INTERRUPTED -> "被中断"
        else -> "已结束"
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        return if (minutes < 1) seconds.toString() + "s" else minutes.toString() + "m" + (seconds % 60) + "s"
    }

    private fun notificationId(runId: String): Int =
        NOTIFICATION_ID_BASE + (runId.hashCode() and 0x3FF)

    private fun openAppIntent(id: Int): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
