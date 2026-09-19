package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.WORKSPACE_JOB_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.job.WorkspaceJobStatus
import me.rerere.rikkahub.utils.sendNotification

private const val TAG = "JobNotification"

/** 后台任务的系统通知（完成/失败/超时/被系统延后）。 */
class WorkspaceJobNotificationManager(
    private val context: Application,
    appScope: AppScope,
    eventBus: AppEventBus,
) {
    init {
        appScope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                runCatching {
                    when (event) {
                        is AppEvent.WorkspaceJobFinished -> notifyFinished(event)
                        is AppEvent.WorkspaceJobDeferred -> notifyDeferred(event)
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
            contentIntent = pendingIntent(event.jobId, event.workspaceId)
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
            contentIntent = pendingIntent(event.jobId, event.workspaceId)
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

    private fun pendingIntent(jobId: String, workspaceId: String): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("workspaceId", workspaceId)
            putExtra("jobId", jobId)
        }
        return PendingIntent.getActivity(
            context,
            notificationId(jobId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
