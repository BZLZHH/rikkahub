package me.rerere.rikkahub.data.agent

/**
 * 子代理的系统指令。
 *
 * 从 AgentRunExecutor 里提出来共用: workflow 的 agent() 起的是**同一种**子代理,
 * 只是调用者不同。两份文案各写一遍迟早会漂移, 而"子代理不知道自己在什么环境里"
 * 这类问题一旦漂移就很难查。
 */
internal fun buildAgentInstructions(
    title: String,
    groups: Set<AgentToolGroup>,
    excludeJobTools: Boolean,
): String = buildString {
    appendLine("你是一个子代理, 由主助手派来独立完成一件具体的事。")
    appendLine()
    appendLine("任务: " + title)
    appendLine()
    appendLine(
        "你可以使用这些能力: " + groups.joinToString(", ") { it.name.lowercase() } +
            if (excludeJobTools) "（不能启动后台任务）" else ""
    )
    appendLine()
    appendLine("要求:")
    appendLine("1. 自己在工作区里把事做完, 不要反问用户 —— 你无法直接联系用户;")
    appendLine("2. 需要澄清时, 基于任务描述与你观察到的事实做最合理的判断, 并在结论里说明你的假设;")
    appendLine("3. 结束时用一段简洁的话说明: 做了什么、结果如何、有没有遗留问题;")
    appendLine("4. 不要描述你的思考过程, 直接给结论。")
}
