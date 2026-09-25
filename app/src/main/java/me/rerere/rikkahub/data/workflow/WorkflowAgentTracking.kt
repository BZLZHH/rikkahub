package me.rerere.rikkahub.data.workflow

/**
 * 一次 agent 调用在 UI 上的身份与上下文。
 *
 * [callIndex] 是"脚本开始这次调用时分配的序号"（不是完成顺序）—— 与可恢复存档用的是同一个序号,
 * 这样 UI 上的一行、存档里的一条、以及重跑时复用的是同一件事, 不会出现三套编号对不上。
 */
data class WorkflowAgentCall(
    val callIndex: Int,
    val prompt: String,
    val schemaJson: String?,
    val label: String?,
    /** 开始这次调用时脚本所处的 phase（脚本没调过 phase() 就是 null） */
    val phase: String?,
    /**
     * 这次调用实际使用的推理档位（已解析并落定）; null 表示继承父助手的设置。
     *
     * 存"落定后的值"而不是"脚本写的字符串": UI 上要显示的是**它到底用了什么**。
     */
    val reasoning: String? = null,
)

/**
 * agent 运行过程中的**实时**上报。
 *
 * 全是同步方法: 它们是在子代理的收集循环里被调用的, 不该在那里等数据库;
 * 真正落库由 [WorkflowAgentTracker] 的定时/收尾那侧负责。
 */
interface WorkflowAgentObserver {
    /** 模型解析出来了（UI 上显示成 deepseek-flash 这种小标签）。 */
    fun onModel(modelId: String, modelName: String) {}

    /** 轮次 / 工具调用 / token 变化。 */
    fun onProgress(steps: Int, toolCalls: Int, tokensIn: Int, tokensOut: Int) {}

    /**
     * 子会话的转录 id。
     *
     * UI 拿它去 [me.rerere.rikkahub.data.agent.AgentTranscriptStore] 读**完整对话** ——
     * 这正是"能看到子会话里发生了什么"的那把钥匙, 所以它在 agent 一启动就要报出来。
     */
    fun onSession(sessionId: String) {}
}

/** 不做任何上报的空实现（单测与"不需要 UI"的场景用）。 */
object NoopWorkflowAgentObserver : WorkflowAgentObserver

/**
 * 把"这次调用发生了什么"落进耐久存储。
 *
 * 与 [WorkflowAgentObserver] 的分工: 观察者是**过程**（高频、同步、可能丢几条也不致命）,
 * 跟踪者是**结论**（低频、挂起、必须准确）。
 */
interface WorkflowAgentTracker {
    /**
     * 调用开始。返回的观察者会被交给 handler; [reused] 为真时不会真的跑 agent。
     */
    suspend fun start(call: WorkflowAgentCall, reused: Boolean): WorkflowAgentObserver

    /**
     * 调用结束, 无论成功、失败还是复用命中都恰好调用一次。
     *
     * @param status 用 [me.rerere.rikkahub.data.run.RunStatus] 的名字
     */
    suspend fun finish(
        call: WorkflowAgentCall,
        reused: Boolean,
        status: String,
        resultText: String?,
        error: String?,
    )
}

/** 不记录任何东西（单测与"只关心结果"的场景用）。 */
object NoopWorkflowAgentTracker : WorkflowAgentTracker {
    override suspend fun start(call: WorkflowAgentCall, reused: Boolean): WorkflowAgentObserver =
        NoopWorkflowAgentObserver

    override suspend fun finish(
        call: WorkflowAgentCall,
        reused: Boolean,
        status: String,
        resultText: String?,
        error: String?,
    ) = Unit
}
