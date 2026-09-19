package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.WORKSPACE_JOB_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.db.dao.WorkspaceJobDAO
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.job.WorkspaceJobStatus
import me.rerere.rikkahub.utils.sendNotification

private const val TAG = "JobNotification"

/** "运行中"汇总通知的固定 id（单个任务完成通知用 3000+ 段） */
private const val RUNNING_NOTIFICATION_ID = 3100

/**
 * 后台任务的系统通知:
 * - 每个任务结束/被延后: 单独一条通知（点击回到应用的任务页）
 * - 有任务在运行时: 一条汇总的 ongoing 通知"当前 N 个任务运行中", 全部结束后自动消失
 */
class WorkspaceJobNotificationManager(
    private val context: Application,
    private val appScope: AppScope,
    eventBus: AppEventBus,
    private val dao: WorkspaceJobDAO,
) {
    init {
        appScope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                runCatching {
                    when (event) {
                        is AppEvent.WorkspaceJobStarted -> refreshRunningNotification()
                        is AppEvent.WorkspaceJobFinished -> {
                            notifyFinished(event)
                            refreshRunningNotification()
                        }

                        is AppEvent.WorkspaceJobDeferred -> {
                            notifyDeferred(event)
                            refreshRunningNotification()
                        }

                        else -> Unit
                    }
                }.onFailure { Log.e(TAG, "notify job event failed", it) }
            }
        }
    }

    private fun notifyFinished(event: AppEvent.WorkspaceJobFinished) {
        if (!event.notify) return
        val status = WorkspaceJobStatus.from(event.status)
        val prefix = when (status) {
            WorkspaceJobStatus.SUCCEEDED -> context.getString(R.string.job_notification_succeeded)
            WorkspaceJobStatus.KILLED -> context.getString(R.string.job_notification_killed)
            WorkspaceJobStatus.TIMED_OUT -> context.getString(R.string.job_notification_timed_out)
            WorkspaceJobStatus.INTERRUPTED -> context.getString(R.string.job_notification_interrupted)
            else -> context.getString(R.string.job_notification_failed)
        }
        val category = if (status == WorkspaceJobStatus.SUCCEEDED) {
            NotificationCompat.CATEGORY_STATUS
        } else {
            NotificationCompat.CATEGORY_ERROR
        }
        val content = buildString {
            append(prefix)
            event.exitCode?.let { append(" · exit=").append(it) }
            event.runtimeMs?.let { append(" · ").append(formatDuration(it)) }
        }
        context.sendNotification(
            channelId = WORKSPACE_JOB_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId(event.jobId),
        ) {
            title = event.name
            this.content = content
            autoCancel = true
            this.category = category
            contentIntent = openJobsIntent(notificationId(event.jobId))
            useBigTextStyle = true
        }
    }

    private fun notifyDeferred(event: AppEvent.WorkspaceJobDeferred) {
        context.sendNotification(
            channelId = WORKSPACE_JOB_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId(event.jobId),
        ) {
            title = event.name
            content = context.getString(R.string.job_notification_deferred)
            autoCancel = true
            category = NotificationCompat.CATEGORY_REMINDER
            contentIntent = openJobsIntent(notificationId(event.jobId))
            useBigTextStyle = true
        }
    }

    /** 运行中任务的汇总通知（ongoing, 无声音）。 */
    private suspend fun refreshRunningNotification() {
        val running = runCatching { dao.listRunningJobs() }.getOrDefault(emptyList())
        if (running.isEmpty()) {
            runCatching {
                NotificationManagerCompat.from(context).cancel(RUNNING_NOTIFICATION_ID)
            }
            return
        }
        val names = running.joinToString(", ") { it.name }.take(160)
        context.sendNotification(
            channelId = WORKSPACE_JOB_NOTIFICATION_CHANNEL_ID,
            notificationId = RUNNING_NOTIFICATION_ID,
        ) {
            title = context.getString(R.string.job_notification_running_title, running.size)
            content = names
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            contentIntent = openJobsIntent(RUNNING_NOTIFICATION_ID)
            useBigTextStyle = true
        }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        return when {
            seconds < 60 -> seconds.toString() + "s"
            seconds < 3600 -> (seconds / 60).toString() + "m"
            else -> (seconds / 3600).toString() + "h" + ((seconds % 3600) / 60) + "m"
        }
    }

    private fun notificationId(jobId: String): Int = 3000 + (jobId.hashCode() and 0x0FFF)

    /** 点击通知打开全局任务页。 */
    private fun openJobsIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("openJobs", true)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
