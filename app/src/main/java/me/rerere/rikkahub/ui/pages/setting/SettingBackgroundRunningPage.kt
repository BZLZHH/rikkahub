package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.BackgroundKeepAliveService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.BackgroundRestrictionUtil
import me.rerere.rikkahub.utils.NotificationUtil
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

private const val UPTIME_REFRESH_INTERVAL_MS = 15_000L

/**
 * 「后台持续运行」引导页。
 *
 * Android 没有一个"后台运行"权限可以一次申请到位，能不能长期驻留后台是若干系统开关的组合结果，
 * 所以这里做成"开关 + 自检清单 + 逐项引导"的形式：
 * 1. 主开关负责开/关常驻前台服务（[BackgroundKeepAliveService]）；
 * 2. 自检清单列出通知权限、电池优化、厂商自启动三项，能检测的直接显示状态与跳转入口；
 * 3. 说明卡片解释常驻通知的来由，避免用户把它当成广告或异常通知。
 */
@Composable
fun SettingBackgroundRunningPage() {
    val context = LocalContext.current
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val running by BackgroundKeepAliveService.running.collectAsStateWithLifecycle()
    val startedAt by BackgroundKeepAliveService.startedAt.collectAsStateWithLifecycle()

    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setOf(PermissionNotification)
        } else {
            emptySet()
        },
    )
    PermissionManager(permissionState = permissionState)

    var notificationGranted by remember {
        mutableStateOf(NotificationUtil.hasNotificationPermission(context))
    }
    var batteryUnrestricted by remember {
        mutableStateOf(BackgroundRestrictionUtil.isIgnoringBatteryOptimizations(context))
    }
    var hasAutoStartPage by remember {
        mutableStateOf(BackgroundRestrictionUtil.hasKnownAutoStartSettings())
    }

    // 从系统设置页返回时重新自检，用户改完权限/电池优化能立刻看到状态变化
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationGranted = NotificationUtil.hasNotificationPermission(context)
                batteryUnrestricted =
                    BackgroundRestrictionUtil.isIgnoringBatteryOptimizations(context)
                hasAutoStartPage = BackgroundRestrictionUtil.hasKnownAutoStartSettings()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt, running) {
        while (running) {
            now = SystemClock.elapsedRealtime()
            delay(UPTIME_REFRESH_INTERVAL_MS)
        }
    }
    val uptimeText = if (startedAt <= 0L) {
        null
    } else {
        val minutes = ((now - startedAt) / 60_000L).coerceAtLeast(0L)
        if (minutes < 60) {
            stringResource(R.string.setting_background_running_uptime_minutes, minutes)
        } else {
            stringResource(
                R.string.setting_background_running_uptime_hours,
                minutes / 60,
                minutes % 60,
            )
        }
    }

    fun startKeepAlive() {
        scope.launch {
            // 先落盘再启动：服务的设置观察者以设置流为准，顺序反了会被立刻停掉
            settingsStore.update { it.copy(backgroundRunningEnabled = true) }
            if (!BackgroundKeepAliveService.start(context)) {
                toaster.show(context.getString(R.string.setting_background_running_start_failed))
                settingsStore.update { it.copy(backgroundRunningEnabled = false) }
            }
        }
    }

    fun stopKeepAlive() {
        scope.launch {
            settingsStore.update { it.copy(backgroundRunningEnabled = false) }
            BackgroundKeepAliveService.stop(context)
        }
    }

    var pendingEnable by remember { mutableStateOf(false) }

    fun onToggle(enabled: Boolean) {
        if (!enabled) {
            stopKeepAlive()
            return
        }
        if (permissionState.allPermissionsGranted) {
            startKeepAlive()
        } else {
            // 通知权限是常驻通知的前提，先走系统权限流程，授权后再启动
            pendingEnable = true
            permissionState.requestPermissions()
        }
    }

    LaunchedEffect(permissionState.allPermissionsGranted) {
        notificationGranted = NotificationUtil.hasNotificationPermission(context)
        if (pendingEnable && permissionState.allPermissionsGranted) {
            pendingEnable = false
            startKeepAlive()
        }
    }

    val checksReady = (if (notificationGranted) 1 else 0) + (if (batteryUnrestricted) 1 else 0)
    val checksTotal = 2

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_background_running)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("switch") {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        leadingContent = { Icon(HugeIcons.Rocket01, contentDescription = null) },
                        headlineContent = {
                            Text(stringResource(R.string.setting_background_running_switch_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_background_running_switch_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.backgroundRunningEnabled,
                                onCheckedChange = { enabled -> onToggle(enabled) },
                            )
                        },
                    )
                }
            }

            item("status") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = {
                        Text(stringResource(R.string.setting_background_running_status_title))
                    },
                ) {
                    item(
                        leadingContent = {
                            Icon(
                                imageVector = if (running) HugeIcons.CheckmarkCircle02 else HugeIcons.Alert01,
                                contentDescription = null,
                                tint = if (running) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        headlineContent = {
                            Text(
                                if (running) {
                                    stringResource(R.string.setting_background_running_status_running)
                                } else {
                                    stringResource(R.string.setting_background_running_status_stopped)
                                }
                            )
                        },
                        supportingContent = {
                            Text(
                                if (running) {
                                    stringResource(R.string.setting_background_running_status_running_desc)
                                } else {
                                    stringResource(R.string.setting_background_running_status_stopped_desc)
                                }
                            )
                        },
                    )
                    if (running && uptimeText != null) {
                        item(
                            leadingContent = { Icon(HugeIcons.Time02, contentDescription = null) },
                            headlineContent = {
                                Text(stringResource(R.string.setting_background_running_uptime_title))
                            },
                            supportingContent = { Text(uptimeText) },
                        )
                    }
                }
            }

            item("checks") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = {
                        Text(
                            if (checksReady >= checksTotal) {
                                stringResource(R.string.setting_background_running_check_summary_all)
                            } else {
                                stringResource(
                                    R.string.setting_background_running_check_summary_pending,
                                    checksTotal - checksReady,
                                )
                            }
                        )
                    },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Notification01, contentDescription = null) },
                        headlineContent = {
                            Text(stringResource(R.string.setting_background_running_check_notification_title))
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (notificationGranted) {
                                        R.string.setting_background_running_check_notification_desc_granted
                                    } else {
                                        R.string.setting_background_running_check_notification_desc_missing
                                    }
                                )
                            )
                        },
                        trailingContent = {
                            if (notificationGranted) {
                                ReadyBadge()
                            } else {
                                TextButton(onClick = { permissionState.requestPermissions() }) {
                                    Text(stringResource(R.string.setting_background_running_check_action_open))
                                }
                            }
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Zap, contentDescription = null) },
                        headlineContent = {
                            Text(stringResource(R.string.setting_background_running_check_battery_title))
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (batteryUnrestricted) {
                                        R.string.setting_background_running_check_battery_desc_granted
                                    } else {
                                        R.string.setting_background_running_check_battery_desc_missing
                                    }
                                )
                            )
                        },
                        trailingContent = {
                            if (batteryUnrestricted) {
                                ReadyBadge()
                            } else {
                                TextButton(
                                    onClick = {
                                        BackgroundRestrictionUtil.openBatteryOptimizationSettings(context)
                                    }
                                ) {
                                    Text(stringResource(R.string.setting_background_running_check_action_open))
                                }
                            }
                        },
                    )
                    if (hasAutoStartPage) {
                        item(
                            leadingContent = { Icon(HugeIcons.Refresh03, contentDescription = null) },
                            headlineContent = {
                                Text(stringResource(R.string.setting_background_running_check_autostart_title))
                            },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.setting_background_running_check_autostart_desc,
                                        BackgroundRestrictionUtil.deviceBrandName(),
                                    )
                                )
                            },
                            trailingContent = {
                                TextButton(
                                    onClick = { BackgroundRestrictionUtil.openAutoStartSettings(context) }
                                ) {
                                    Text(stringResource(R.string.setting_background_running_check_action_open))
                                }
                            },
                        )
                    }
                }
            }

            item("about") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = {
                        Text(stringResource(R.string.setting_background_running_about_title))
                    },
                ) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_background_running_about_notification_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_background_running_about_notification_desc))
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_background_running_about_cost_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_background_running_about_cost_desc))
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_background_running_about_exit_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_background_running_about_exit_desc))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyBadge() {
    Icon(
        imageVector = HugeIcons.CheckmarkCircle02,
        contentDescription = stringResource(R.string.setting_background_running_done),
        tint = MaterialTheme.colorScheme.primary,
    )
}
