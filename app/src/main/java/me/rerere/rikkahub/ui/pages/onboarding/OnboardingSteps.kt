package me.rerere.rikkahub.ui.pages.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiSecurity01
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.workspace.RootfsCatalog
import me.rerere.rikkahub.data.workspace.RootfsDistro
import me.rerere.rikkahub.data.workspace.RootfsMirror
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage

// ---------------- 0. 欢迎 ----------------

@Composable
fun WelcomeStep() {
    // 整屏错峰入场: 一齐出现会显得很平, 错开一点就有节奏了
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StaggeredIn(0, shown) {
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(78.dp),
                )
            }
        }

        StaggeredIn(1, shown) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "RikkaHub Enhanced",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "把 AI 放进你的手机",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StaggeredIn(2, shown) {
                FeatureRow(
                    icon = HugeIcons.Cpu,
                    title = "任意模型",
                    caption = "OpenAI · Gemini · DeepSeek, 填个 Key 就能用",
                )
            }
            StaggeredIn(3, shown) {
                FeatureRow(
                    icon = HugeIcons.Folder01,
                    title = "工作区沙箱",
                    caption = "AI 在里面读写文件、跑命令、跑长任务",
                )
            }
            StaggeredIn(4, shown) {
                FeatureRow(
                    icon = HugeIcons.AiSecurity01,
                    title = "权限由你定",
                    caption = "问不问看档位与规则；后台任务先看范围",
                )
            }
        }
    }
}

/** 错峰入场: index 越大出现越晚, 每档 70ms。 */
@Composable
private fun StaggeredIn(index: Int, visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(ONBOARD_ANIM_MS, delayMillis = index * 70)) +
            slideInVertically(tween(ONBOARD_ANIM_MS, delayMillis = index * 70)) { height -> height / 5 },
    ) {
        content()
    }
}

/** 一行"图标 + 标题 + 说明"。比纯文字列表显著得多, 也不用读一整段。 */
@Composable
private fun FeatureRow(icon: ImageVector, title: String, caption: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------- 1. 模型 ----------------

@Composable
fun ModelStep(
    preset: ModelPreset,
    onPresetChange: (ModelPreset) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    modelId: String,
    onModelIdChange: (String) -> Unit,
    testing: Boolean,
    testResult: String?,
    testOk: Boolean,
    onTest: () -> Unit,
) {
    var revealKey by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StepTitle(
            title = "配置模型",
            subtitle = "填一个 API Key 就能开始。下面会自动建好提供商与模型, 不需要你手工加。",
        )

        SectionLabel("选一个服务")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelPreset.entries.take(2).forEach { option ->
                SelectCard(
                    title = option.label,
                    subtitle = option.hint,
                    selected = option == preset,
                    modifier = Modifier.weight(1f),
                    onClick = { onPresetChange(option) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelPreset.entries.drop(2).forEach { option ->
                SelectCard(
                    title = option.label,
                    subtitle = option.hint,
                    selected = option == preset,
                    modifier = Modifier.weight(1f),
                    onClick = { onPresetChange(option) },
                )
            }
        }

        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = if (revealKey) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = { revealKey = !revealKey }) {
                    Text(if (revealKey) "隐藏" else "显示")
                }
            },
        )

        if (preset == ModelPreset.CUSTOM) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Base URL（OpenAI 兼容）") },
                placeholder = { Text("https://example.com/v1") },
                singleLine = true,
            )
        }

        OutlinedTextField(
            value = modelId,
            onValueChange = onModelIdChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("模型 id") },
            placeholder = { Text(preset.modelId.ifBlank { "gpt-4o-mini" }) },
            singleLine = true,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                onClick = onTest,
                enabled = !testing && apiKey.isNotBlank() && (preset != ModelPreset.CUSTOM || baseUrl.isNotBlank()),
            ) {
                Text("测试连接")
            }
            if (testing) {
                CircularProgressIndicator(
                    modifier = Modifier.height(16.dp),
                    strokeWidth = 2.dp,
                )
            }
            testResult?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (testOk) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }

        if (apiKey.isBlank()) {
            InfoCard(
                title = "还没有填 Key",
                body = "可以先跳过 —— 但那样进主界面后什么也发不出去。" +
                    "完成页会再提醒你一次。",
                container = MaterialTheme.colorScheme.tertiaryContainer,
            )
        }
    }
}

// ---------------- 2. 工作区 ----------------

@Composable
fun WorkspaceStep(
    name: String,
    onNameChange: (String) -> Unit,
    distro: RootfsDistro,
    onDistroChange: (RootfsDistro) -> Unit,
    arch: String,
    onArchChange: (String) -> Unit,
    deviceArch: String,
    mirror: RootfsMirror,
    onMirrorChange: (RootfsMirror) -> Unit,
    url: String?,
    installing: Boolean,
    progress: RootfsInstallProgress?,
    error: String?,
    onSkipInstall: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StepTitle(
            title = "创建工作区",
            subtitle = "AI 读写文件、跑命令都在这个沙箱里。没有它, 那些工具会静默失效。",
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

        SectionLabel("下载源")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RootfsMirror.entries.forEach { option ->
                SelectChip(
                    text = option.label + " · " + option.note,
                    selected = option == mirror,
                    enabled = !installing,
                    onClick = { onMirrorChange(option) },
                )
            }
        }

        SectionLabel("架构")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RootfsCatalog.arches.forEach { candidate ->
                SelectChip(
                    text = RootfsCatalog.archLabel(candidate) +
                        if (candidate == deviceArch) " · 本机" else "",
                    selected = candidate == arch,
                    enabled = !installing,
                    onClick = { onArchChange(candidate) },
                )
            }
        }
        Text(
            text = if (deviceArch == arch) {
                "已按本机架构预选。装错架构会解压成功、shell 却起不来。"
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
                text = "这个组合没有现成构建, 换一个发行版或架构。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = url,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (installing) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InstallProgress(progress)
                    Text(
                        text = "下载上百 MB, 可以随时取消或跳过, 之后在「设置 → 工作区」再装。",
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
private fun InstallProgress(progress: RootfsInstallProgress?) {
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
        // 让进度条自己走完那 200ms, 字节跳变看着就不那么"顿"
        val fraction by animateFloatAsState(
            targetValue = (read.toFloat() / total.toFloat()).coerceIn(0f, 1f),
            animationSpec = tween(ONBOARD_ANIM_MS),
            label = "installProgress",
        )
        LinearProgressIndicator(
            progress = { fraction },
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
                    (progress.currentEntry?.let { " · " + it.takeLast(34) } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------- 3. 外观 ----------------

@Composable
fun AppearanceStep(
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    colorMode: ColorMode,
    onColorModeChange: (ColorMode) -> Unit,
    themeId: String,
    onThemeChange: (String) -> Unit,
    amoled: Boolean,
    onAmoledChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StepTitle(
            title = "外观",
            subtitle = "改了立刻生效 —— 你看到的这个界面本身就会跟着变。",
        )

        SectionLabel("深浅色")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorMode.entries.forEach { mode ->
                SelectChip(
                    text = when (mode) {
                        ColorMode.SYSTEM -> "跟随系统"
                        ColorMode.LIGHT -> "浅色"
                        ColorMode.DARK -> "深色"
                    },
                    selected = mode == colorMode,
                    onClick = { onColorModeChange(mode) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("动态取色", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "从壁纸取色（Android 12+）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = dynamicColor, onCheckedChange = onDynamicColorChange)
        }

        if (colorMode == ColorMode.DARK) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("纯黑背景", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "OLED 省电, 对比更强",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = amoled, onCheckedChange = onAmoledChange)
            }
        }

        if (!dynamicColor) {
            SectionLabel("主题色")
            PresetThemes.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { theme ->
                        ThemeSwatch(
                            selected = theme.id == themeId,
                            modifier = Modifier.weight(1f),
                            onClick = { onThemeChange(theme.id) },
                            colors = listOf(
                                theme.standardLight.primary,
                                theme.standardLight.secondary,
                                theme.standardLight.tertiary,
                            ),
                            label = theme.name,
                        )
                    }
                    if (row.size == 1) {
                        Box(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeSwatch(
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    colors: List<Color>,
    label: @Composable () -> Unit,
) {
    val border by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0f)
        },
        animationSpec = tween(ONBOARD_ANIM_MS),
        label = "swatchBorder",
    )
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                colors.forEach { color ->
                    Box(
                        Modifier
                            .height(18.dp)
                            .weight(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(color),
                    )
                }
            }
            label()
        }
    }
}

// ---------------- 4. 权限 ----------------

@Composable
fun PermissionsStep(
    notificationsGranted: Boolean,
    onRequestNotifications: () -> Unit,
    batteryExempt: Boolean,
    onRequestBattery: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StepTitle(
            title = "通知与后台",
            subtitle = "这两项决定「任务跑完了你会不会知道」, 以及它会不会被系统掐掉。",
        )

        PermissionRow(
            title = "通知权限",
            body = "workflow、子代理、后台任务结束时靠它通知你。不授权会安静地什么都不发生。",
            granted = notificationsGranted,
            grantedLabel = "已授权",
            actionLabel = "去授权",
            onAction = onRequestNotifications,
        )

        PermissionRow(
            title = "常驻后台（电池优化白名单）",
            body = "不加白名单时, 长时间运行的任务在 Doze 下会被系统掐断, 表现为「跑着跑着就没了」。",
            granted = batteryExempt,
            grantedLabel = "已加入白名单",
            actionLabel = "去设置",
            onAction = onRequestBattery,
        )

        Text(
            text = "两项都可以之后在系统设置里改; 跳过不影响聊天。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    body: String,
    granted: Boolean,
    grantedLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = if (granted) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (granted) {
                Text(
                    text = grantedLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

// ---------------- 5. 完成 ----------------

@Composable
fun DoneStep(rows: List<Pair<String, String>>, noModel: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StepTitle(title = "准备就绪", subtitle = "下面这些随时可以在设置里改。")
        rows.forEach { (label, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (noModel) {
            InfoCard(
                title = "还差一步: 配置模型",
                body = "现在还没有可用的模型, 所以暂时发不出消息。" +
                    "进主界面后到「设置 → 提供商」填一个 API Key 就行。",
                container = MaterialTheme.colorScheme.tertiaryContainer,
            )
        }
    }
}

private fun megabytes(bytes: Long): String =
    String.format("%.1f MB", bytes.toDouble() / 1024.0 / 1024.0)
