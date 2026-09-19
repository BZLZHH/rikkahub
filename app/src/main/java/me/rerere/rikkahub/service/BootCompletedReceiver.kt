package me.rerere.rikkahub.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.context.GlobalContext

private const val TAG = "BootCompletedReceiver"

/**
 * 开机 / 应用升级后恢复「后台持续运行」。
 *
 * 只在用户显式开启过常驻开关（[SettingsStore] 里的 backgroundRunningEnabled）时才启动，
 * 并且和 [RikkaHubApp] 里的启动逻辑一致：没有通知权限就不启动（Android 13+ 前台服务通知
 * 是用户体验的一部分，静默跑一个用户看不见的常驻服务不如不跑）。
 *
 * 说明：specialUse 类型的前台服务不在 Android 15 的"禁止从 BOOT_COMPLETED 启动"名单里，
 * 因此这里可以合法启动；不支持或抛异常时只记日志，不影响开机流程。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val koin = runCatching { GlobalContext.get() }.getOrNull() ?: return@launch
                val settings = koin.get<SettingsStore>().settingsFlowRaw.first()
                if (!settings.backgroundRunningEnabled) {
                    Log.i(TAG, "Background running is disabled, skip auto start")
                    return@launch
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(TAG, "Notification permission not granted, skip auto start")
                    return@launch
                }
                BackgroundKeepAliveService.start(context)
                Log.i(TAG, "Background keep-alive service restored after $action")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore background keep-alive service", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
