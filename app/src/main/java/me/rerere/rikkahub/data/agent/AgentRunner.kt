package me.rerere.rikkahub.data.agent

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "AgentRunner"

/**
 * 子代理超出轮次预算。
 *
 * 单独一个异常类型是为了让上层能把它与"真实失败"区分开 —— 预算耗尽应当记
 * TIMED_OUT（受资源上限限制）而不是 FAILED（做错了事）。
 */
class AgentBudgetExceededException(
    val rounds: Int,
    val maxRounds: Int,
) : RuntimeException("agent budget exceeded: " + rounds + "/" + maxRounds)

/** 子代理一次运行的产物流。 */
sealed interface AgentEvent {
    /** 子代理的可见输出(累积后的完整消息列表)。 */
    data class Messages(val messages: List<UIMessage>) : AgentEvent

    /** 内部进度: 已完成的 AI 轮次 / 工具调用次数 / token 用量。 */
    data class Progress(
        val steps: Int,
        val toolCalls: Int,
        val promptTokens: Int,
        val completionTokens: Int,
    ) : AgentEvent

    /** 结束: 最终文本。 */
    data class Finished(val text: String) : AgentEvent
}

/**
 * 子代理的执行体: 用 [GenerationLoop] 跑一个**独立的上下文窗口**。
 *
 * 与主会话的区别只有三处, 其余全部复用:
 * 1. 上下文从零开始 —— 只带系统指令 + 任务, 不带父会话历史(这正是"子代理"的意义);
 * 2. 工具集受限（见 [AgentToolPolicy]）;
 * 3. 有明确的预算闸（maxSteps）。
 *
 * 刻意不改 [GenerationLoop]: 那是主会话跑得好好的路径, 动它的风险远大于收益。
 */
class AgentRunner(
    private val generationLoop: GenerationLoop,
    private val transcripts: AgentTranscriptStore,
) {
    fun run(
        runId: String,
        settings: Settings,
        model: Model,
        parentAssistant: Assistant,
        tools: List<Tool>,
        instructions: String,
        prompt: String,
        cwd: String?,
        maxSteps: Int,
    ): Flow<AgentEvent> = flow {
        val assistant = parentAssistant.copy(
            systemPrompt = instructions,
            // 不复用记忆与历史会话: 会把父会话上下文泄漏进子代理
            enableMemory = false,
            enableRecentChatsReference = false,
            enabledSkills = emptySet(),
            allowConversationSystemPrompt = false,
        )

        writeTranscript(runId, "start", buildJsonObject {
            put("model", model.id.toString())
            put("tools", tools.joinToString(", ") { it.name })
            put("maxSteps", maxSteps)
            put("prompt", prompt)
        })

        var steps = 0
        var toolCalls = 0
        var promptTokens = 0
        var completionTokens = 0
        var lastMessages: List<UIMessage> = emptyList()
        // 预算闸: GenerationLoop 的 maxSteps 只约束**单次生成**内的循环步数,
        // 约束不了"整段运行"的总轮次。总预算必须在这里自己盯 —— 否则一个不停调用工具的
        // 子代理会无限跑下去, 烧光 token 也占着并发配额。
        val budget = AgentBudget(maxRounds = maxSteps)

        generationLoop.generateText(
            settings = settings,
            model = model,
            messages = listOf(UIMessage.user(prompt)),
            assistant = assistant,
            tools = tools,
            // 给 GenerationLoop 一个宽松的内部上限; **总预算由下面的预算闸盯**
            maxSteps = maxSteps * 2,
            workspaceCwd = cwd,
        ).collect { chunk ->
            when (chunk) {
                is GenerationChunk.Messages -> {
                    lastMessages = chunk.messages
                    steps = lastMessages.count { it.role == MessageRole.ASSISTANT }
                    toolCalls = lastMessages.sumOf { it.getTools().size }
                    lastMessages.asReversed().firstNotNullOfOrNull { it.usage }?.let { usage ->
                        promptTokens = usage.promptTokens
                        completionTokens = usage.completionTokens
                    }
                    writeTranscript(runId, "step", buildJsonObject {
                        put("steps", steps)
                        put("toolCalls", toolCalls)
                        put("promptTokens", promptTokens)
                        put("completionTokens", completionTokens)
                        put("tail", lastMessages.lastOrNull()?.toText()?.take(2_000) ?: "")
                    })
                    emit(AgentEvent.Messages(lastMessages))
                    val lastAssistant = lastMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                    val exceeded = budget.onAssistantMessage(
                        lastAssistant?.let { it.getTools().isNotEmpty() },
                    )
                    if (exceeded) {
                        writeTranscript(runId, "budget", buildJsonObject {
                            put("rounds", budget.rounds)
                            put("maxSteps", maxSteps)
                        })
                        throw AgentBudgetExceededException(budget.rounds, maxSteps)
                    }
                }
            }
        }

        emit(AgentEvent.Progress(steps, toolCalls, promptTokens, completionTokens))
        writeTranscript(runId, "finish", buildJsonObject {
            put("steps", steps)
            put("toolCalls", toolCalls)
            put("promptTokens", promptTokens)
            put("completionTokens", completionTokens)
        })
        val text = lastMessages
            .lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.toText()
            .orEmpty()
        emit(AgentEvent.Finished(text))
    }

    private fun writeTranscript(runId: String, type: String, payload: JsonObject) {
        runCatching {
            val obj = buildJsonObject {
                put("ts", System.currentTimeMillis())
                put("type", type)
                payload.forEach { (k, v) -> put(k, v) }
            }
            transcripts.append(runId, JsonInstant.encodeToString(JsonObject(obj)))
        }.onFailure { Log.w(TAG, "transcript append failed for " + runId, it) }
    }
}
