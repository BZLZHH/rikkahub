package me.rerere.rikkahub.ui.pages.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.workspace.RootfsCatalog
import me.rerere.rikkahub.data.workspace.RootfsDistro
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val STEP_WELCOME = 0
private const val STEP_WORKSPACE = 1
private const val STEP_DONE = 2

/**
 * 首次启动向导。
 *
 * 为什么要有它: 这个 App 里给 AI 用的东西几乎全挂在**工作区**上 —— 文件读写、shell、
 * 后台任务、子代理与 workflow 的落脚点。但第一次打开时一个工作区都没有, 用户看到的是一堆
 * 工具静默失效, 而且没有任何地方告诉他"你还缺一个工作区"。
 *
 * 所以向导的重点不是欢迎词, 而是**把工作区这件事在第一次就办掉**。
 * 发行版默认 Arch Linux（用户指定）; 架构默认取**设备自己那一份** —— 后者是必须的:
 * 装错架构会解压成功但 shell 起不来, 而错误信息完全指不到这一点。
 *
 * 全程可跳过: 装 rootfs 是上百 MB 的下载, 不该拦着人进主界面。
 */
@Composable
fun OnboardingPage() {
    val settingsStore: SettingsStore = koinInject()
    val repository: WorkspaceRepository = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var step by rememberSaveable { mutableIntStateOf(STEP_WELCOME) }
    var name by rememberSaveable { mutableStateOf("workspace") }
    var distroName by rememberSaveable { mutableStateOf(RootfsCatalog.defaultDistro.name) }
    var arch by rememberSaveable { mutableStateOf(RootfsCatalog.deviceArch()) }
    var createdId by rememberSaveable { mutableStateOf<String?>(null) }
    var createdName by rememberSaveable { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<RootfsInstallProgress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var installJob by remember { mutableStateOf<Job?>(null) }

    val distro = runCatching { RootfsDistro.valueOf(distroName) }.getOrDefault(RootfsCatalog.defaultDistro)
    val url = RootfsCatalog.url(distro, arch)
    val deviceArch = remember { RootfsCatalog.deviceArch() }
    val noModel = settings.providers.none { provider -> provider.models.isNotEmpty() }

    /** 收尾: 把工作区绑给还没有工作区的助手, 然后记下"向导走完了"。 */
    suspend fun finish() {
        val id = createdId
        settingsStore.update { current ->
            current.copy(
                assistants = if (id == null) {
                    current.assistants
                } else {
                    val uuid = runCatching { Uuid.parse(id) }.getOrNull()
                    current.assistants.map { assistant ->
                        if (assistant.workspaceId == null && uuid != null) {
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

    /** 只建工作区, 不装 rootfs（文件工具立刻可用; shell 等以后再装）。 */
    fun createOnly() {
        error = null
        scope.launch {
            runCatching {
                createdId?.let { repository.getById(it) } ?: repository.create(name)
            }.onSuccess { workspace ->
                createdId = workspace.id
                createdName = workspace.name
                step = STEP_DONE
            }.onFailure { e ->
                error = e.message ?: "创建工作区失败"
            }
        }
    }

    fun startInstall() {
        val target = url ?: return
        error = null
        installing = true
        progress = null
        installJob = scope.launch {
            runCatching {
                val workspace = createdId?.let { repository.getById(it) } ?: repository.create(name)
                createdId = workspace.id
                createdName = workspace.name
                repository.installRootfs(workspace.id, target) { snapshot -> progress = snapshot }
            }.onSuccess {
                installed = true
                step = STEP_DONE
            }.onFailure { e ->
                error = e.message ?: e::class.simpleName ?: "安装失败"
            }
            installing = false
            installJob = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (step > STEP_WELCOME) {
                        TextButton(onClick = { step -= 1 }, enabled = !installing) { Text("上一步") }
                    }
                    Box(Modifier.weight(1f))
                    if (step < STEP_DONE) {
                        TextButton(
                            onClick = {
                                installJob?.cancel()
                                scope.launch { finish() }
                            },
                            enabled = !installing,
                        ) { Text("跳过") }
                    }
                    when (step) {
                        STEP_WELCOME -> Button(onClick = { step = STEP_WORKSPACE }) { Text("开始") }
                        STEP_WORKSPACE -> Button(
                            onClick = { startInstall() },
                            enabled = !installing && name.isNotBlank() && url != null,
                        ) { Text(if (installing) "安装中…" else "创建并安装") }
                        else -> Button(onClick = { scope.launch { finish() } }) { Text("开始使用") }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepDots(step)
            when (step) {
                STEP_WELCOME -> WelcomeStep()
                STEP_WORKSPACE -> WorkspaceStep(
                    name = name,
                    onNameChange = { name = it },
                    distro = distro,
                    onDistroChange = { distroName = it.name },
                    arch = arch,
                    onArchChange = { arch = it },
                    deviceArch = deviceArch,
                    url = url,
                    installing = installing,
                    progress = progress,
                    error = error,
                    onSkipInstall = { createOnly() },
                )
                else -> DoneStep(
                    workspaceName = createdName,
                    installed = installed,
                    noModel = noModel,
                )
            }
        }
    }
}

@Composable
private fun StepDots(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { index ->
            Box(
                Modifier
                    .size(width = if (index == step) 22.dp else 8.dp, height = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (index <= step) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    ),
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("欢迎使用 RikkaHub", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "先说一件最要紧的事: 这个 App 里给 AI 用的文件读写、shell、后台任务、" +
                "子代理与 workflow, 全都挂在一个叫「工作区」的东西上。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Bullet("工作区 = 一个隔离的目录 + 一个 Linux 环境（rootfs）")
        Bullet("没有工作区时那些工具会静默失效, 而且不会有明显的报错")
        Bullet("下一步就把它建起来, 发行版默认 Arch Linux")
        Text(
            text = "全程可以跳过, 之后在「设置 → 工作区」里随时能补。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("·", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WorkspaceStep(
    name: String,
    onNameChange: (String) -> Unit,
    distro: RootfsDistro,
    onDistroChange: (RootfsDistro) -> Unit,
    arch: String,
    onArchChange: (String) -> Unit,
    deviceArch: String,
    url: String?,
    installing: Boolean,
    progress: RootfsInstallProgress?,
    error: String?,
    onSkipInstall: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("创建工作区", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "给它起个名字, 选一个 Linux 发行版。装好后 AI 就能在这个环境里读写文件、跑命令。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("工作区名字") },
            singleLine = true,
            enabled = !installing,
        )

        SectionLabel("发行版")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RootfsDistro.entries.forEach { option ->
                SelectCard(
                    title = option.label,
                    subtitle = option.note,
                    selected = option == distro,
                    enabled = !installing,
                    modifier = Modifier.weight(1f),
                    onClick = { onDistroChange(option) },
                )
            }
        }

        SectionLabel("架构")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RootfsCatalog.arches.forEach { candidate ->
                SelectChip(
                    text = RootfsCatalog.archLabel(candidate) +
                        if (candidate == deviceArch) "  · 本机" else "",
                    selected = candidate == arch,
                    enabled = !installing,
                    onClick = { onArchChange(candidate) },
                )
            }
        }
        Text(
            text = if (deviceArch == arch) {
                "已按本机架构预选 —— 装错架构会解压成功但 shell 起不来。"
            } else {
                "注意: 这不是本机架构（本机是 " + RootfsCatalog.archLabel(deviceArch) + "）。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (deviceArch == arch) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )

        if (url == null) {
            Text(
                text = "这个发行版没有 " + RootfsCatalog.archLabel(arch) + " 的构建, 换一个组合。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                text = url,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (installing) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProgressBlock(progress)
                    Text(
                        text = "下载可能上百 MB, 可以先跳过、以后在「设置 → 工作区」里再装。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (!installing) {
            TextButton(onClick = onSkipInstall) { Text("跳过安装, 只建工作区") }
        }
    }
}

@Composable
private fun ProgressBlock(progress: RootfsInstallProgress?) {
    val stage = when (progress?.stage) {
        RootfsInstallStage.DOWNLOADING -> "正在下载 rootfs…"
        RootfsInstallStage.EXTRACTING -> "正在解压…"
        RootfsInstallStage.INSTALLED -> "安装完成"
        null -> "正在连接…"
    }
    Text(stage, style = MaterialTheme.typography.bodyMedium)
    val total = progress?.totalBytes ?: 0L
    val read = progress?.bytesRead ?: 0L
    if (progress?.stage == RootfsInstallStage.DOWNLOADING && total > 0) {
        LinearProgressIndicator(
            progress = { read.toFloat() / total.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = megabytes(read) + " / " + megabytes(total),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (progress?.stage == RootfsInstallStage.EXTRACTING) {
            Text(
                text = (progress.entriesExtracted.toString() + " 个文件") +
                    (progress.currentEntry?.let { " · " + it.takeLast(38) } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DoneStep(workspaceName: String?, installed: Boolean, noModel: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("准备就绪", style = MaterialTheme.typography.headlineSmall)
        if (workspaceName == null) {
            Text(
                text = "这次没有建工作区。文件与 shell 类工具会一直不可用, 直到你在「设置 → 工作区」里建一个。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            DoneRow("工作区已创建", workspaceName)
            DoneRow(
                label = "Linux 环境",
                value = if (installed) "已安装" else "未安装（可稍后在 设置 → 工作区 里补）",
            )
        }
        if (noModel) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("还差一步: 配置模型", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "现在还没有可用的模型。进主界面后到「设置 → 提供商」里填一个 API Key, 就能开始聊天了。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DoneRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SelectCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (selected) {
                    Modifier.border(1.5.dp, accent, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                }
            )
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun megabytes(bytes: Long): String =
    String.format("%.1f MB", bytes.toDouble() / 1024.0 / 1024.0)
