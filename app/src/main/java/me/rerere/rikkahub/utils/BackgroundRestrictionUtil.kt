package me.rerere.rikkahub.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.getSystemService

private const val TAG = "BackgroundRestriction"

/**
 * 后台运行限制的自检与跳转。
 *
 * Android 上并没有"后台运行权限"这类运行时权限，应用能否长期驻留后台由若干系统开关共同决定：
 * 电池优化（Doze / 省电策略）、厂商自启动管理、以及通知权限。
 *
 * 这里只做「检测状态 + 引导用户跳转到系统设置」，不做任何静默申请。尤其刻意不声明
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：它属于 Google Play 受限权限，且不少 ROM
 * 会直接忽略 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，还不如直接把用户带到
 * 系统的电池优化列表里手动选择"不限制"来得可靠。
 */
object BackgroundRestrictionUtil {

    private data class OemAutoStart(
        val brand: String,
        val components: List<String>,
    )

    /**
     * 各厂商的"自启动 / 后台运行"管理页。这些 Activity 都不是公开 API，随 ROM 版本变动，
     * 因此逐个尝试并在解析失败时退回应用详情页。
     */
    private val KNOWN_AUTO_START = listOf(
        OemAutoStart(
            "xiaomi",
            listOf("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
        OemAutoStart(
            "redmi",
            listOf("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
        OemAutoStart(
            "poco",
            listOf("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
        OemAutoStart(
            "huawei",
            listOf(
                "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
        ),
        OemAutoStart(
            "honor",
            listOf(
                "com.hihonor.systemmanager/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        ),
        OemAutoStart(
            "oppo",
            listOf(
                "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity",
            ),
        ),
        OemAutoStart(
            "realme",
            listOf(
                "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
            ),
        ),
        OemAutoStart(
            "oneplus",
            listOf("com.oneplus.security/com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        ),
        OemAutoStart(
            "vivo",
            listOf(
                "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            ),
        ),
        OemAutoStart(
            "iqoo",
            listOf(
                "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            ),
        ),
        OemAutoStart(
            "meizu",
            listOf(
                "com.meizu.safe/com.meizu.safe.security.SmartPermissionActivity",
                "com.meizu.safe/com.meizu.safe.permission.PermissionAppAllPermissionActivity",
            ),
        ),
        OemAutoStart(
            "samsung",
            listOf(
                "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
                "com.samsung.android.sm_cn/com.samsung.android.sm.ui.battery.BatteryActivity",
            ),
        ),
        OemAutoStart("asus", listOf("com.asus.mobilemanager/com.asus.mobilemanager.MainActivity")),
        OemAutoStart(
            "letv",
            listOf("com.letv.android.letvsafe/com.letv.android.letvsafe.AutobootManageActivity"),
        ),
    )

    /** 是否已加入电池优化白名单（true = 系统不会在省电时限制它）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService<PowerManager>() ?: return false
        return runCatching {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }

    /** 厂商 / 品牌可读名称，用于引导文案。 */
    fun deviceBrandName(): String {
        val name = Build.MANUFACTURER.ifBlank { Build.BRAND }.trim()
        if (name.isEmpty()) return "This device"
        return name.replaceFirstChar { it.uppercase() }
    }

    /** 当前机型是否有已知的自启动管理页。 */
    fun hasKnownAutoStartSettings(): Boolean = autoStartComponentNames().isNotEmpty()

    private fun appDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    /** 打开应用详情页（所有机型都有，作为兜底）。 */
    fun openAppDetails(context: Context) {
        context.startFirstResolvable(listOf(appDetailsIntent(context)))
    }

    /** 打开系统的电池优化列表。 */
    fun openBatteryOptimizationSettings(context: Context) {
        context.startFirstResolvable(
            listOf(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
                appDetailsIntent(context),
            )
        )
    }

    /** 打开厂商的自启动 / 后台运行管理页，找不到就退回应用详情页。 */
    fun openAutoStartSettings(context: Context) {
        val intents = autoStartComponentNames().map { name ->
            Intent().setComponent(ComponentName.unflattenFromString(name))
        }
        context.startFirstResolvable(intents + appDetailsIntent(context))
    }

    private fun autoStartComponentNames(): List<String> {
        val identity = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return KNOWN_AUTO_START
            .filter { identity.contains(it.brand) }
            .flatMap { it.components }
    }

    private fun Context.startFirstResolvable(intents: List<Intent>) {
        intents.forEach { intent ->
            val resolvable = runCatching { intent.resolveActivity(packageManager) }.getOrNull() != null
            if (!resolvable) return@forEach
            val started = runCatching {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (started.isSuccess) return
            Log.w(TAG, "Unable to start $intent", started.exceptionOrNull())
        }
        // 全部失败时退回应用详情页
        runCatching {
            startActivity(appDetailsIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.e(TAG, "Unable to open app details settings", it) }
    }
}
