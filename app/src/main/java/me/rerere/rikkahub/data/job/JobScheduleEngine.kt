package me.rerere.rikkahub.data.job

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.dao.WorkspaceJobDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceJobDefEntity
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.JobScheduleReceiver
import me.rerere.workspace.CronExpression
import java.time.ZoneId

private const val TAG = "JobScheduleEngine"

/**
 * 定时/周期触发器。
 *
 * 统一用 AlarmManager 的"单次闹钟 + 触发后重排"模型（interval 与 cron 都归约成"算出下次时间"）:
 * - 有精确闹钟权限时用 setExactAndAllowWhileIdle, 否则退化为 setAndAllowWhileIdle（Doze 下允许漂移）;
 * - 设备重启、应用升级后由 BootCompletedReceiver 调 [rescheduleAll] 重排;
 * - 触发时若后台无法确保常驻前台服务, 由 [WorkspaceJobManager.startFromDef] 记 DEFERRED, 等常驻开启后补跑。
 */
class JobScheduleEngine(
    private val context: Context,
    private val appScope: AppScope,
    private val dao: WorkspaceJobDAO,
    private val manager: WorkspaceJobManager,
    eventBus: AppEventBus,
) {
    companion object {
        const val ACTION_RUN_DEF = "me.rerere.rikkahub.action.JOB_RUN_DEF"
        const val EXTRA_DEF_ID = "def_id"
        private const val REQUEST_CODE_BASE = 0x4A0000
    }

    init {
        appScope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                if (event is AppEvent.WorkspaceJobFinished && event.defId != null) {
                    runCatching { onJobFinished(event) }
                        .onFailure { Log.e(TAG, "update def stats failed", it) }
                }
            }
        }
    }

    fun nextFireAt(def: WorkspaceJobDefEntity, after: Long): Long? = when (val trigger = def.trigger()) {
        is JobTrigger.Delay -> after + trigger.seconds * 1_000L
        is JobTrigger.Interval -> {
            val start = trigger.startAt ?: after
            if (start > after) start else start + ((after - start) / (trigger.seconds * 1_000L) + 1) * trigger.seconds * 1_000L
        }
        is JobTrigger.Once -> trigger.at.takeIf { it > after }
        is JobTrigger.Cron -> runCatching {
            CronExpression.parse(trigger.expr).nextFireAt(
                afterMillis = after,
                zone = trigger.tz?.let { ZoneId.of(it) } ?: ZoneId.systemDefault(),
            )
        }.getOrNull()
        null -> null
    }

    suspend fun rescheduleAll() {
        dao.listScheduledDefs().forEach { def ->
            runCatching { reschedule(def) }.onFailure { Log.e(TAG, "reschedule ${def.name} failed", it) }
        }
    }

    suspend fun reschedule(def: WorkspaceJobDefEntity) {
        val next = if (def.enabled) nextFireAt(def, System.currentTimeMillis()) else null
        dao.upsertDef(def.copy(nextRunAt = next, updatedAt = System.currentTimeMillis()))
        if (next == null) {
            cancel(def.id)
        } else {
            setAlarm(def.id, next)
        }
    }

    fun cancel(defId: String) {
        alarmManager()?.let { runCatching { it.cancel(pendingIntent(defId)) } }
    }

    /** 闹钟触发: 跑一次, 并为周期任务安排下一次。 */
    suspend fun onAlarm(defId: String) {
        val def = dao.getDef(defId) ?: return
        if (!def.enabled) {
            cancel(defId)
            return
        }
        runCatching { manager.startFromDef(def, triggerSource = JobTriggerSource.SCHEDULE) }
            .onFailure { Log.e(TAG, "scheduled run of ${def.name} failed", it) }
        reschedule(dao.getDef(defId) ?: def)
    }

    private suspend fun onJobFinished(event: AppEvent.WorkspaceJobFinished) {
        val defId = event.defId ?: return
        val def = dao.getDef(defId) ?: return
        val finishedAt = System.currentTimeMillis()
        val updated = def.copy(
            lastRunAt = finishedAt,
            runCount = def.runCount + 1,
            lastStatus = event.status,
            updatedAt = finishedAt,
        )
        // 一次性触发器跑完即失效, 避免又被排上下一次
        val oneShot = def.trigger() is JobTrigger.Delay || def.trigger() is JobTrigger.Once
        val next = if (oneShot) null else nextFireAt(updated, finishedAt)
        dao.upsertDef(updated.copy(nextRunAt = next))
        if (next == null) cancel(defId) else setAlarm(defId, next)
    }

    private fun setAlarm(defId: String, at: Long) {
        val manager = alarmManager() ?: return
        val pi = pendingIntent(defId)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }.onFailure {
            Log.w(TAG, "exact alarm failed, falling back to inexact", it)
            runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    private fun alarmManager(): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private fun pendingIntent(defId: String): PendingIntent {
        val intent = Intent(context, JobScheduleReceiver::class.java).apply {
            action = ACTION_RUN_DEF
            putExtra(EXTRA_DEF_ID, defId)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BASE + defId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
