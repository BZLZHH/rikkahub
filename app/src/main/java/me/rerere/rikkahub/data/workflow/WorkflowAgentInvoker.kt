package me.rerere.rikkahub.data.workflow

import android.util.Log
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.agent.AgentBudgetExceededException
import me.rerere.rikkahub.data.agent.AgentEvent
import me.rerere.rikkahub.data.agent.AgentRunner
import me.rerere.rikkahub.data.agent.AgentToolFactory
import me.rerere.rikkahub.data.agent.AgentToolGroup
import me.rerere.rikkahub.data.agent.buildAgentInstructions
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import kotlin.uuid.Uuid

private const val TAG = "WorkflowAgentInvoker"

/**
 * workflow 的 agent() 真正干活的地方。
 *
 * 与 subagent_* 工具最关键的区别: 这里是**同步**的 —— 脚本 await 的就是这一次调用的结果,
 * 而不是"起一个后台子代理, 之后再去查状态"。这正是 workflow 的意义: 中间结果留在脚本变量里,
 * 不进任何人的上下文。所以这里刻意**不走** RunOrchestrator 的"启动-看护-收尾"三段式,
 * 那套是给"用户可见的后台任务"用的（要配额、要通知、要能单独 kill）。
 *
 * 代价要说清楚: workflow 里的 agent 不出现在 subagent_status 列表里。
 * 并发由 [WorkflowRuntime] 的 agent 闸门统一排队（大小取自用户配的子代理并发）,
 * 所以"脚本扇出得再猛也不会越过用户设的上限"这条仍然成立。
 */
class WorkflowAgentInvoker(
    private val runner: AgentRunner,
    /**
     * **延迟解析**工具工厂, 不能直接注入实例。
     *
     * 直接注入会成环: ChatToolFactory -> WorkflowRunner -> WorkflowAgentInvoker
     * -> AgentToolFactory -> ChatToolFactory, 而 Koin 解析单例是**递归**的,
     * 结果就是 StackOverflowError（真机上崩过一次, 别改回去）。
     *
     * 这里延迟是天然成立的: 只有在**真的要跑一个 agent** 时才需要它,
     * 而那一刻整个图早就构造完了。同一个套路见 RunOrchestrator 的 executorFactories。
     */
    private val toolFactory: () -> AgentToolFactory,
    private val settingsStore: SettingsStore,
) {

    /**
     * 跑一个子代理并等它结束。
     *
     * 返回 null 的语义与上游一致: 被停止或不可恢复错误（脚本据此判断, 不当作异常）。
     */
    suspend fun run(
        conversationId: String?,
        assistantId: String?,
        cwd: String?,
        maxSteps: Int,
        prompt: String,
        label: String?,
    ): String? {
        val settings = settingsStore.settingsFlowRaw.first()
        val parentAssistant = settings.assistants.firstOrNull { it.id.toString() == assistantId }
            ?: settings.assistants.firstOrNull()
            ?: return null
        // 与子代理同一策略: 默认用"快速模型" —— workflow 的 agent 是内部劳动力,
        // 几十上百个调用都吃主模型会把额度烧穿。
        val model = settings.findModelById(null, settings.fastModelId) ?: return null

        val groups = setOf(AgentToolGroup.WORKSPACE)
        // 工具白名单与子代理共用: workflow_* / subagent_* / ask_user 永远不会发给它们
        // （见 AgentToolPolicy）, 所以脚本里的 agent 不可能递归起 workflow, 也不会卡在人工确认上。
        val tools = toolFactory().createTools(
            settings = settings,
            assistant = parentAssistant,
            model = model,
            groups = groups,
            excludeJobTools = false,
            cwd = cwd,
            conversationId = conversationId,
        )

        val runId = Uuid.random().toString()
        var text: String? = null
        return runCatching {
            runner.run(
                runId = runId,
                settings = settings,
                model = model,
                parentAssistant = parentAssistant,
                tools = tools,
                instructions = buildAgentInstructions(label ?: "workflow step", groups, false),
                prompt = prompt,
                cwd = cwd,
                maxSteps = maxSteps,
            ).collect { event ->
                if (event is AgentEvent.Finished) text = event.text
            }
            text
        }.getOrElse { error ->
            // 预算耗尽与真实失败都归到"没有产出": 脚本看到的是 null, 与上游一致。
            // 但日志要区分开, 否则排查时看不出是"被预算掐了"还是"真出错了"。
            val kind = if (error is AgentBudgetExceededException) "budget exceeded" else "failed"
            Log.w(TAG, "workflow agent " + runId + " " + kind, error)
            null
        }
    }
}
