package me.rerere.rikkahub.data.agent

/**
 * 子代理的轮次预算。
 *
 * 抽成独立的小状态机是为了**能被单测**: 这段逻辑原先内联在 AgentRunner 的流回调里,
 * 而 AgentRunner 依赖真实的 GenerationLoop, 没法直接测 —— 一条"会烧钱"的规则却测不了,
 * 是不可接受的。
 *
 * 为什么要它: GenerationLoop 的 maxSteps 只约束**单次生成**内的循环步数,
 * 约束不了整段运行。一个不停调用工具的子代理会无限跑下去, 烧 token 又占并发配额。
 */
class AgentBudget(
    val maxRounds: Int,
) {
    var rounds: Int = 0
        private set

    private var sawToolCallsInRound = false

    /**
     * 观察一次助手回合。
     *
     * "一轮"的判定: 助手消息里出现了工具调用 -> 记为本轮有工具;
     * 随后助手**不再**带工具调用（说明上一轮工具已执行完、模型给了新回复）-> 计一轮完成。
     *
     * @param assistantHasToolCalls 该助手消息是否带工具调用; null 表示本次事件不是助手消息
     * @return true 表示已超出预算, 调用方应当中止
     */
    fun onAssistantMessage(assistantHasToolCalls: Boolean?): Boolean {
        if (assistantHasToolCalls == null) return false
        if (assistantHasToolCalls) {
            sawToolCallsInRound = true
            return false
        }
        if (!sawToolCallsInRound) return false
        sawToolCallsInRound = false
        rounds++
        return rounds >= maxRounds
    }
}
