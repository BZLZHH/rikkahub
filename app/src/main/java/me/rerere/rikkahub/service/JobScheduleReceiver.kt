package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.job.JobScheduleEngine
import org.koin.core.context.GlobalContext

private const val TAG = "JobScheduleReceiver"

/** 定时任务的闹钟入口。 */
class JobScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val defId = intent.getStringExtra(JobScheduleEngine.EXTRA_DEF_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val koin = runCatching { GlobalContext.get() }.getOrNull() ?: return@launch
                koin.get<JobScheduleEngine>().onAlarm(defId)
            } catch (e: Exception) {
                Log.e(TAG, "scheduled trigger failed for def=$defId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
