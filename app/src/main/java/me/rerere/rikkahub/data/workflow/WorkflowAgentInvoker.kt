package me.rerere.rikkahub.data.workflow

import android.util.Log
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.agent.AgentBudgetExceededException
import me.rerere.rikkahub.data.agent.AgentEvent
import me.rerere.rikkahub.data.agent.AgentRunner
import me.rerere.rikkahub.data.agent.AgentToolFactory
import me.rerere.rikkahub.data.agent.AgentToolGroup
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.agent.buildAgentInstructions
import me.rerere.rikkahub.data.agent.snapshotOf
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
        call: WorkflowAgentCall,
        observer: WorkflowAgentObserver,
        /** 这次 workflow 选定的模型; null 表示退回"快速模型" */
        modelId: Uuid?,
        conversationId: String?,
        assistantId: String?,
        cwd: String?,
        maxSteps: Int,
    ): String? {
        val prompt = call.prompt
        val label = call.label
        val settings = settingsStore.settingsFlowRaw.first()
        val parentAssistant = settings.assistants.firstOrNull { it.id.toString() == assistantId }
            ?: settings.assistants.firstOrNull()
            ?: return null
        // 模型由**这一次运行**决定（见 WorkflowRunRequest.modelSpec）:
        // 默认就是主会话当前那个 —— 用户说"用 workflow 做 X"时, 想的是"用我正在聊的模型"。
        // 只有调用方没给（老调用点/异常情况）才退回快速模型。
        val model = settings.findModelById(modelId, settings.fastModelId) ?: return null

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

        // 推理档位: 只有这一条**指定了**才覆盖, 否则原样继承父助手的 ——
        // "不指定就用你正在用的那一档"是最不容易出意外的默认。
        // AgentRunner 会在 parentAssistant 基础上再 copy, 所以这里换掉父助手即可生效。
        val agentParent = call.reasoning
            ?.let { ReasoningLevel.fromSpec(it) }
            ?.let { parentAssistant.copy(reasoningLevel = it) }
            ?: parentAssistant

        val runId = Uuid.random().toString()
        // 一有 runId 就报出去: UI 要用它去读**完整子会话**。
        // 这一刻子系统里还什么都没有, 但 UI 会自己去轮询文件, 所以先给钥匙最重要。
        observer.onSession(runId)
        observer.onModel(model.id.toString(), model.displayName)

        var text: String? = null
        return runCatching {
            runner.run(
                runId = runId,
                settings = settings,
                model = model,
                parentAssistant = agentParent,
                tools = tools,
                instructions = buildAgentInstructions(label ?: "workflow step", groups, false),
                prompt = prompt,
                cwd = cwd,
                maxSteps = maxSteps,
            ).collect { event ->
                when (event) {
                    is AgentEvent.Messages -> {
                        // 每轮都报一次。原先这里写的是 `-> Unit`（丢掉）, 只靠整段结束时那一次
                        // AgentEvent.Progress —— 结果是运行期间界面一直显示 0 tok / 0 工具调用,
                        // 直到结束才跳一下。数字本来就在这份累积列表里, 现算即可。
                        val snapshot = snapshotOf(event.messages)
                        observer.onProgress(
                            steps = snapshot.steps,
                            toolCalls = snapshot.toolCalls,
                            tokensIn = snapshot.tokensIn,
                            tokensOut = snapshot.tokensOut,
                        )
                    }
                    is AgentEvent.Progress -> observer.onProgress(
                        steps = event.steps,
                        toolCalls = event.toolCalls,
                        tokensIn = event.promptTokens,
                        tokensOut = event.completionTokens,
                    )
                    is AgentEvent.Finished -> text = event.text
                }
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
