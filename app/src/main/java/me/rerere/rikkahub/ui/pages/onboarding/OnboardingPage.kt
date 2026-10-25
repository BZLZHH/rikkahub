package me.rerere.rikkahub.ui.pages.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.workspace.RootfsCatalog
import me.rerere.rikkahub.data.workspace.RootfsDistro
import me.rerere.rikkahub.data.workspace.RootfsMirror
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.workspace.RootfsInstallProgress
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val STEP_COUNT = 6
private const val STEP_WELCOME = 0
private const val STEP_MODEL = 1
private const val STEP_WORKSPACE = 2
private const val STEP_APPEARANCE = 3
private const val STEP_PERMISSIONS = 4
private const val STEP_DONE = 5

/**
 * 首次启动向导（完整引导）。
 *
 * 为什么是这六步, 而不是只建个工作区:
 * 这个 App 第一次打开时**四件事同时是空的** —— 没有模型（一句话都发不出去）、
 * 没有工作区（所有文件/命令类工具静默失效）、没有通知权限（后台跑完了不会告诉你）、
 * 没有后台白名单（长任务会被系统掐掉）。它们失败的方式都是"什么都不发生",
 * 用户几乎不可能自己查出来。所以引导的价值不在于欢迎词, 而在于**把这四件事一次办完**。
 *
 * 每一步都可跳过（模型那步跳过时会在完成页留下明显提醒）。
 */
@Composable
fun OnboardingPage() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settingsStore: SettingsStore = koinInject()
    val repository: WorkspaceRepository = koinInject()
    val providerManager: ProviderManager = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val amoledState = rememberAmoledDarkMode()
    var colorMode by rememberColorMode()

    var step by rememberSaveable { mutableIntStateOf(STEP_WELCOME) }

    // 模型
    var presetName by rememberSaveable { mutableStateOf(ModelPreset.DEEPSEEK.name) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var modelId by rememberSaveable { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }

    // 工作区
    var wsName by rememberSaveable { mutableStateOf("workspace") }
    var distroName by rememberSaveable { mutableStateOf(RootfsCatalog.defaultDistro.name) }
    var arch by rememberSaveable { mutableStateOf(RootfsCatalog.deviceArch()) }
    var mirrorName by rememberSaveable { mutableStateOf(RootfsCatalog.defaultMirror.name) }
    var createdId by rememberSaveable { mutableStateOf<String?>(null) }
    var createdName by rememberSaveable { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<RootfsInstallProgress?>(null) }
    var wsError by remember { mutableStateOf<String?>(null) }
    var installJob by remember { mutableStateOf<Job?>(null) }

    // 权限（真实系统状态, 不是我们自己记的标记）
    var notifGranted by remember { mutableStateOf(context.hasNotificationPermission()) }
    var batteryExempt by remember { mutableStateOf(context.isBatteryExempt()) }

    val preset = runCatching { ModelPreset.valueOf(presetName) }.getOrDefault(ModelPreset.DEEPSEEK)
    val distro = runCatching { RootfsDistro.valueOf(distroName) }.getOrDefault(RootfsCatalog.defaultDistro)
    val mirror = runCatching { RootfsMirror.valueOf(mirrorName) }.getOrDefault(RootfsCatalog.defaultMirror)
    val url = RootfsCatalog.url(distro, arch, mirror)
    val deviceArch = remember { RootfsCatalog.deviceArch() }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notifGranted = granted }

    // 从系统设置返回时刷新一下白名单状态 —— 不然用户明明加了, 界面还说没加
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifGranted = context.hasNotificationPermission()
                batteryExempt = context.isBatteryExempt()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun runConnectionTest() {
        testing = true
        testResult = null
        scope.launch {
            runCatching {
                val preview = previewProvider(preset, apiKey, baseUrl)
                providerManager.getProviderByType(preview).listModels(preview)
            }.onSuccess { models ->
                testOk = true
                testResult = "连接成功 · " + models.size + " 个模型可用"
            }.onFailure { e ->
                testOk = false
                val reason = e.message ?: e::class.simpleName ?: "未知错误"
                testResult = "失败: " + reason.take(90)
            }
            testing = false
        }
    }

    fun createOnly() {
        wsError = null
        scope.launch {
            runCatching {
                createdId?.let { repository.getById(it) } ?: repository.create(wsName)
            }.onSuccess { workspace ->
                createdId = workspace.id
                createdName = workspace.name
                step = STEP_APPEARANCE
            }.onFailure { e ->
                wsError = e.message ?: "创建工作区失败"
            }
        }
    }

    fun startInstall() {
        val target = url ?: return
        wsError = null
        installing = true
        progress = null
        installJob = scope.launch {
            runCatching {
                val workspace = createdId?.let { repository.getById(it) } ?: repository.create(wsName)
                createdId = workspace.id
                createdName = workspace.name
                repository.installRootfs(workspace.id, target) { snapshot -> progress = snapshot }
            }.onSuccess {
                installed = true
                step = STEP_APPEARANCE
            }.onFailure { e ->
                wsError = e.message ?: e::class.simpleName ?: "安装失败"
            }
            installing = false
            installJob = null
        }
    }

    /** 收尾: 存模型 → 绑工作区 → 标记走完。
 */
    suspend fun finish() {
        val workspaceId = createdId
        // 预先取一次快照: settingsStore.update 的变换不是挂起函数, 里面查不了库。
        // 它的用处是识别"助手指向了一个已经被删掉的工作区" —— 那种情况下只判断 null 是不够的,
        // 助手会一直指着一个不存在的工作区, 而用户重新走一遍向导正是想修这个。
        val liveWorkspaceIds = runCatching {
            repository.listFlow().first().map { it.id }.toSet()
        }.getOrDefault(emptySet())
        settingsStore.update { current ->
            val withModel = if (apiKey.isNotBlank()) {
                applyModelPreset(current, preset, apiKey, baseUrl, modelId)
            } else {
                current
            }
            val uuid = workspaceId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            withModel.copy(
                assistants = if (uuid == null) {
                    withModel.assistants
                } else {
                    withModel.assistants.map { assistant ->
                        val bound = assistant.workspaceId?.toString()
                        if (bound == null || bound !in liveWorkspaceIds) {
                            assistant.copy(workspaceId = uuid)
                        } else {
                            assistant
                        }
                    }
                },
                onboardingCompleted = true,
            )
        }
    }

    val skipTo = { target: Int ->
        installJob?.cancel()
        step = target
    }

    val primaryLabel = when (step) {
        STEP_WELCOME -> "开始"
        STEP_MODEL -> "下一步"
        STEP_WORKSPACE -> if (createdId != null) "下一步" else if (installing) "安装中…" else "创建并安装"
        STEP_DONE -> "开始使用"
        else -> "下一步"
    }

    OnboardingScaffold(
        step = step,
        totalSteps = STEP_COUNT,
        onBack = if (step == STEP_WELCOME) null else { { step -= 1 } },
        onSkip = if (step >= STEP_DONE) {
            null
        } else {
            { skipTo(if (step == STEP_WORKSPACE) STEP_APPEARANCE else step + 1) }
        },
        primaryLabel = primaryLabel,
        primaryEnabled = !installing && (step != STEP_WORKSPACE || createdId != null || url != null),
        onPrimary = {
            when (step) {
                STEP_WORKSPACE -> if (createdId != null) step = STEP_APPEARANCE else startInstall()
                STEP_DONE -> scope.launch { finish() }
                else -> step += 1
            }
        },
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                val direction = if (forward) 1 else -1
                val enter = slideInHorizontally(tween(ONBOARD_ANIM_MS)) { width ->
                    direction * width / 6
                } + fadeIn(tween(ONBOARD_ANIM_MS))
                val exit = slideOutHorizontally(tween(ONBOARD_ANIM_MS)) { width ->
                    -direction * width / 6
                } + fadeOut(tween(ONBOARD_ANIM_MS))
                enter togetherWith exit
            },
            label = "onboardingStep",
        ) { current ->
            when (current) {
                STEP_WELCOME -> WelcomeStep()

                STEP_MODEL -> ModelStep(
                    preset = preset,
                    onPresetChange = {
                        presetName = it.name
                        testResult = null
                    },
                    apiKey = apiKey,
                    onApiKeyChange = {
                        apiKey = it
                        testResult = null
                    },
                    baseUrl = baseUrl,
                    onBaseUrlChange = { baseUrl = it },
                    modelId = modelId,
                    onModelIdChange = { modelId = it },
                    testing = testing,
                    testResult = testResult,
                    testOk = testOk,
                    onTest = { runConnectionTest() },
                )

                STEP_WORKSPACE -> WorkspaceStep(
                    name = wsName,
                    onNameChange = { wsName = it },
                    distro = distro,
                    onDistroChange = { distroName = it.name },
                    arch = arch,
                    onArchChange = { arch = it },
                    deviceArch = deviceArch,
                    mirror = mirror,
                    onMirrorChange = { mirrorName = it.name },
                    url = url,
                    installing = installing,
                    progress = progress,
                    error = wsError,
                    onSkipInstall = { createOnly() },
                )

                STEP_APPEARANCE -> AppearanceStep(
                    dynamicColor = settings.dynamicColor,
                    onDynamicColorChange = { enabled ->
                        scope.launch { settingsStore.update { it.copy(dynamicColor = enabled) } }
                    },
                    colorMode = colorMode,
                    onColorModeChange = { mode -> colorMode = mode },
                    themeId = settings.themeId,
                    onThemeChange = { id ->
                        scope.launch { settingsStore.update { it.copy(themeId = id) } }
                    },
                    amoled = amoledState.value,
                    onAmoledChange = { amoledState.value = it },
                )

                STEP_PERMISSIONS -> PermissionsStep(
                    notificationsGranted = notifGranted,
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            notifGranted = true
                        }
                    },
                    batteryExempt = batteryExempt,
                    onRequestBattery = { context.requestBatteryExemption() },
                )

                else -> DoneStep(
                    rows = listOf(
                        "模型" to if (apiKey.isNotBlank()) preset.label + " · " + modelId.ifBlank { preset.modelId } else "未配置",
                        "工作区" to (createdName ?: "未创建"),
                        "Linux 环境" to if (installed) "已安装" else "未安装",
                        "通知" to if (notifGranted) "已授权" else "未授权",
                        "后台白名单" to if (batteryExempt) "已加入" else "未加入",
                    ),
                    noModel = settings.providers.none { provider -> provider.models.isNotEmpty() } && apiKey.isBlank(),
                )
            }
        }
    }
}

private fun Context.hasNotificationPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}

private fun Context.isBatteryExempt(): Boolean {
    val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return power.isIgnoringBatteryOptimizations(packageName)
}

/**
 * 申请电池优化豁免。
 *
 * 优先用直接弹窗那条（体验最好）; 部分 ROM 会禁掉它, 于是退回到白名单列表页 ——
 * 让用户自己找一下, 总好过点了没反应。
 */
private fun Context.requestBatteryExemption() {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:" + packageName),
    )
    runCatching { startActivity(direct) }.onFailure {
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
