package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.MinusSign
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.run.RunQuota
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * 「后台执行」设置页: 并发配额。
 *
 * 为什么做成用户可调: shell 后台任务与子代理是**分账**的两种执行体
 * （一条长跑的构建不该把子代理饿死，反之亦然），而合适的额度取决于用户自己的用法 ——
 * 有人跑一堆短命令，有人只用一个子代理做长研究。写死常量只能迁就一种人。
 *
 * 硬上限存在的原因是保护设备: 并发过高会让沙箱、模型请求与前台服务一起抖动。
 */
@Composable
fun SettingBackgroundExecutionPage() {
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun apply(updated: Settings) {
        scope.launch { settingsStore.update(updated) }
    }

    // CardGroup 的 content lambda 不是 @Composable, 所以文案必须在这里先取好再传进去。
    val jobsSection = stringResource(R.string.setting_background_execution_jobs_section)
    val jobsGlobal = stringResource(R.string.setting_background_execution_jobs_global)
    val jobsGlobalDesc = stringResource(R.string.setting_background_execution_jobs_global_desc)
    val jobsWorkspace = stringResource(R.string.setting_background_execution_jobs_workspace)
    val jobsWorkspaceDesc = stringResource(R.string.setting_background_execution_jobs_workspace_desc)
    val agentsSection = stringResource(R.string.setting_background_execution_agents_section)
    val agentsGlobal = stringResource(R.string.setting_background_execution_agents_global)
    val agentsGlobalDesc = stringResource(R.string.setting_background_execution_agents_global_desc)
    val agentsWorkspace = stringResource(R.string.setting_background_execution_agents_workspace)
    val agentsWorkspaceDesc = stringResource(R.string.setting_background_execution_agents_workspace_desc)
    val noteTitle = stringResource(R.string.setting_background_execution_note_title)
    val noteDesc = stringResource(R.string.setting_background_execution_note_desc)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_background_execution_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("jobs") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(jobsSection) },
                ) {
                    stepper(
                        headline = jobsGlobal,
                        supporting = jobsGlobalDesc,
                        value = settings.maxConcurrentJobs,
                        max = RunQuota.MAX_CONCURRENT_LIMIT,
                        onChange = { apply(settings.copy(maxConcurrentJobs = it)) },
                    )
                    stepper(
                        headline = jobsWorkspace,
                        supporting = jobsWorkspaceDesc,
                        value = settings.maxConcurrentJobsPerWorkspace,
                        max = RunQuota.MAX_CONCURRENT_PER_WORKSPACE_LIMIT,
                        onChange = { apply(settings.copy(maxConcurrentJobsPerWorkspace = it)) },
                    )
                }
            }

            item("agents") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(agentsSection) },
                ) {
                    stepper(
                        headline = agentsGlobal,
                        supporting = agentsGlobalDesc,
                        value = settings.maxConcurrentAgents,
                        max = RunQuota.MAX_CONCURRENT_LIMIT,
                        onChange = { apply(settings.copy(maxConcurrentAgents = it)) },
                    )
                    stepper(
                        headline = agentsWorkspace,
                        supporting = agentsWorkspaceDesc,
                        value = settings.maxConcurrentAgentsPerWorkspace,
                        max = RunQuota.MAX_CONCURRENT_PER_WORKSPACE_LIMIT,
                        onChange = { apply(settings.copy(maxConcurrentAgentsPerWorkspace = it)) },
                    )
                }
            }

            item("notes") {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        headlineContent = { Text(noteTitle) },
                        supportingContent = { Text(noteDesc) },
                    )
                }
            }
        }
    }
}

/**
 * 加减式数值设置: 配额是"小整数、有硬上限", 用滑杆反而不好精确选。
 *
 * 注意**不是** @Composable: 它在 CardGroup 的非 composable DSL lambda 里被调用;
 * 里面的 { Text(...) } 只是作为 lambda 传给 item 存起来, 稍后才在组合期执行。
 */
private fun CardGroupScope.stepper(
    headline: String,
    supporting: String,
    value: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    item(
        headlineContent = { Text(headline) },
        supportingContent = { Text(supporting) },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = { onChange((value - 1).coerceAtLeast(1)) },
                    enabled = value > 1,
                ) {
                    Icon(HugeIcons.MinusSign, contentDescription = null)
                }
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(
                    onClick = { onChange((value + 1).coerceAtMost(max)) },
                    enabled = value < max,
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                }
            }
        },
    )
}
