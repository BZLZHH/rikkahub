package me.rerere.rikkahub.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.BACKGROUND_KEEP_ALIVE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.job.WorkspaceJobManager
import me.rerere.rikkahub.utils.NotificationUtil
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

private const val TAG = "BackgroundKeepAlive"

/** 常驻通知里"已运行时长"的刷新间隔。 */
private const val NOTIFICATION_REFRESH_INTERVAL_MS = 30_000L

/**
 * 「后台持续运行」常驻前台服务。
 *
 * 它本身不承载业务逻辑，只做两件事：
 * 1. 用一条常驻（ongoing）通知把进程放进 Android 的前台服务白名单，避免应用退到后台后
 *    被系统冻结 / 回收，让 [ChatService] 的流式生成、聊天通知和长任务能继续跑完；
 * 2. 把运行状态暴露给设置页的引导界面，并在通知栏提供「停止」入口。
 *
 * 设计取舍：
 * - 使用 **specialUse** 前台服务类型。[ChatGenerationForegroundService] 用的 dataSync
 *   在 Android 15+ 有单次 6 小时超时限制（onTimeout 会被强制回调），常驻场景需要长期运行，
 *   specialUse 没有这个限制。
 * - **不申请 WakeLock**：前台服务已把进程留在前台待机分桶，网络与长连接能正常调度；
 *   常驻服务本身不跑任何网络请求，持有 WakeLock 只会白白耗电。
 * - 服务与设置双向同步：用户关闭设置开关（或从通知栏点「停止」）都会让服务退出；
 *   反之进程被系统回收后 START_STICKY 重建时会重新读取设置决定去留。
 */
class BackgroundKeepAliveService : Service() {

    companion object {
        private const val ACTION_START = "me.rerere.rikkahub.action.BACKGROUND_KEEP_ALIVE_START"
        private const val ACTION_STOP = "me.rerere.rikkahub.action.BACKGROUND_KEEP_ALIVE_STOP"

        const val NOTIFICATION_ID = 2003

        private val _running = MutableStateFlow(false)

        /** 服务是否正在运行（同进程内共享给引导界面）。 */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _startedAt = MutableStateFlow(0L)

        /** 启动时刻（[SystemClock.elapsedRealtime]），0 表示未运行。 */
        val startedAt: StateFlow<Long> = _startedAt.asStateFlow()

        /** 请求启动常驻服务，返回是否成功发起。 */
        fun start(context: Context): Boolean {
            val intent = Intent(context, BackgroundKeepAliveService::class.java).apply {
                action = ACTION_START
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                Log.e(TAG, "Unable to start background keep-alive service", it)
            }.getOrDefault(false)
        }

        /** 请求停止常驻服务。 */
        fun stop(context: Context) {
            val intent = Intent(context, BackgroundKeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Unable to stop background keep-alive service", it)
            }
        }
    }

    private val settingsStore: SettingsStore by inject()
    private val eventBus: AppEventBus by inject()
    private val jobManager: WorkspaceJobManager by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var settingsObserverJob: Job? = null
    private var generationObserverJob: Job? = null
    private var jobObserverJob: Job? = null
    private var notificationTickerJob: Job? = null

    /** 后台任务运行数（用于在常驻通知里显示）。 */
    private var runningJobs = 0

    /** 正在生成回复的会话（用于在常驻通知上显示"正在生成"）。 */
    private val activeGenerations = linkedSetOf<Uuid>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    settingsStore.update { it.copy(backgroundRunningEnabled = false) }
                }
                stopKeepAlive()
                return START_NOT_STICKY
            }

            else -> {
                // ACTION_START，或进程/服务被系统回收后由 START_STICKY 重建（intent == null）。
                // 无论哪条路径都先进入前台，再由设置观察者决定是否需要退出。
                if (!enterForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        settingsObserverJob?.cancel()
        generationObserverJob?.cancel()
        notificationTickerJob?.cancel()
        synchronized(activeGenerations) { activeGenerations.clear() }
        if (_running.value) {
            _running.value = false
            _startedAt.value = 0L
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---- 前台 / 通知 ----

    private fun enterForeground(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            if (!_running.value) {
                _startedAt.value = SystemClock.elapsedRealtime()
            }
            _running.value = true
            observeSettings()
            observeGenerations()
            observeJobs()
            startNotificationTicker()
            true
        } catch (e: Exception) {
            // 部分 OEM ROM 即使 Manifest 已声明 FOREGROUND_SERVICE_SPECIAL_USE 也会拒绝，
            // 这里必须自行兜底，否则会触发 ForegroundServiceDidNotStartInTimeException
            Log.e(TAG, "Failed to enter foreground", e)
            false
        }
    }

    /** 设置里的开关是唯一事实来源：关掉就退出，避免服务和界面状态不一致。 */
    private fun observeSettings() {
        if (settingsObserverJob != null) return
        settingsObserverJob = serviceScope.launch {
            settingsStore.settingsFlowRaw.collect { settings ->
                if (!settings.backgroundRunningEnabled) {
                    Log.i(TAG, "Background running disabled in settings, stopping service")
                    stopKeepAlive()
                }
            }
        }
    }

    private fun observeGenerations() {
        if (generationObserverJob != null) return
        generationObserverJob = serviceScope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                val changed = when (event) {
                    is AppEvent.ChatGenerationUpdate ->
                        synchronized(activeGenerations) { activeGenerations.add(event.conversationId) }

                    is AppEvent.ChatGenerationEnded ->
                        synchronized(activeGenerations) { activeGenerations.remove(event.conversationId) }

                    else -> false
                }
                if (changed) refreshNotification()
            }
        }
    }

    /** 常驻通知同时反映后台任务运行情况。 */
    private fun observeJobs() {
        if (jobObserverJob != null) return
        jobObserverJob = serviceScope.launch {
            jobManager.runningCount.collect { count ->
                runningJobs = count
                refreshNotification()
            }
        }
    }

    private fun startNotificationTicker() {
        if (notificationTickerJob != null) return
        notificationTickerJob = serviceScope.launch {
            while (isActive) {
                delay(NOTIFICATION_REFRESH_INTERVAL_MS)
                refreshNotification()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshNotification() {
        if (!NotificationUtil.hasNotificationPermission(this)) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }.onFailure {
            Log.w(TAG, "Unable to refresh keep-alive notification", it)
        }
    }

    private fun buildNotification(): Notification {
        val generating = synchronized(activeGenerations) { activeGenerations.size }
        val parts = buildList {
            if (runningJobs > 0) {
                add(getString(R.string.notification_background_keep_alive_jobs, runningJobs))
            }
            if (generating > 0) {
                add(getString(R.string.notification_background_keep_alive_generating, generating))
            }
        }
        val content = parts.joinToString(" · ")
            .ifEmpty { getString(R.string.notification_background_keep_alive_text) }
        return NotificationCompat.Builder(this, BACKGROUND_KEEP_ALIVE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(getString(R.string.notification_background_keep_alive_title))
            .setContentText(content)
            .setSubText(getString(R.string.notification_background_keep_alive_uptime, formatUptime()))
            .setContentIntent(buildContentIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .addAction(
                0,
                getString(R.string.notification_background_keep_alive_stop),
                buildStopPendingIntent(),
            )
            .build()
    }

    private fun formatUptime(): String {
        val started = _startedAt.value
        if (started <= 0L) return getString(R.string.notification_background_keep_alive_uptime_unknown)
        val minutes = ((SystemClock.elapsedRealtime() - started) / 60_000L).coerceAtLeast(0L)
        return if (minutes < 60) {
            getString(R.string.setting_background_running_uptime_minutes, minutes)
        } else {
            getString(
                R.string.setting_background_running_uptime_hours,
                minutes / 60,
                minutes % 60,
            )
        }
    }

    private fun buildContentIntent(): PendingIntent {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun buildStopPendingIntent(): PendingIntent {
        val intent = Intent(this, BackgroundKeepAliveService::class.java).apply {
            action = ACTION_STOP
        }
        return PendingIntent.getService(
            this,
            NOTIFICATION_ID + 1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopKeepAlive() {
        settingsObserverJob?.cancel()
        settingsObserverJob = null
        generationObserverJob?.cancel()
        generationObserverJob = null
        jobObserverJob?.cancel()
        jobObserverJob = null
        notificationTickerJob?.cancel()
        notificationTickerJob = null
        synchronized(activeGenerations) { activeGenerations.clear() }
        if (_running.value) {
            _running.value = false
            _startedAt.value = 0L
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
        stopSelf()
    }
}
