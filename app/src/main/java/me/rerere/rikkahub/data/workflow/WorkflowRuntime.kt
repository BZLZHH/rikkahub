package me.rerere.rikkahub.data.workflow

import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.utils.JsonInstant

/** 脚本想要一个子代理干活时, 运行时需要提供的唯一能力。 */
fun interface WorkflowAgentHandler {
    /**
     * 跑一个子代理并等它结束。
     *
     * @param prompt 交给子代理的指令
     * @param schemaJson 结构化输出要求（JSON Schema 字符串）; null 表示要散文
     * @param label 进度视图里的名字
     * @return 子代理的产出; 被停止或不可恢复错误时返回 null（脚本据此判断）
     */
    suspend fun run(prompt: String, schemaJson: String?, label: String?): String?
}

/** 运行时的进度回调（阶段 / 日志 / agent 计数）。 */
interface WorkflowProgressSink {
    fun onPhase(title: String)
    fun onLog(message: String, level: String)

    /** agent 计数变化。默认空实现, 免得每个调用方都被迫实现它。 */
    fun onAgentProgress(started: Int, reused: Int) {}
}

/** 一次脚本执行的结果。 */
data class WorkflowExecutionResult(
    val ok: Boolean,
    /** 脚本 return 的值（JSON 文本）; 失败时为 null */
    val resultJson: String?,
    /** 失败原因 */
    val error: String?,
    /** 这次运行里 agent 的调用次数与其中复用了存档的次数（可恢复性的可观测证据） */
    val agentsCalled: Int = 0,
    val agentsReused: Int = 0,
)

/** 超出了单次运行的硬上限（agent 调用总数等）。 */
class WorkflowLimitException(message: String) : RuntimeException(message)

/** 结构化输出重试耗尽。 */
class WorkflowSchemaException(message: String) : RuntimeException(message)

/**
 * workflow 脚本的运行时。
 *
 * 桥接方式基于实测（见 WorkflowRuntimeNotes）:
 * - 宿主 async 绑定**会在 evaluate 期间自行跑完**（实测 invoked=1 completed=1）;
 * - 但 evaluate 会在"await 之后的 JS 续体"执行前返回（实测返回 <pending>）;
 * - 所以: 脚本把结果写进 globalThis, 宿主**再求值一次**即可读到 —— 不需要自己搭事件循环。
 *
 * 脚本的 JS 侧适配（agent/pipeline/parallel/护栏）全在 [WorkflowScriptPrelude] 里。
 *
 * 并发**不按脚本的扇出方式限流**, 而是在 agent 闸门（[agentConcurrency]）里统一排队:
 * 无论脚本用 pipeline、parallel 还是手写 Promise.all, 实际同时在跑的 agent 数都一样。
 */
class WorkflowRuntime(
    private val agentHandler: WorkflowAgentHandler,
    private val progress: WorkflowProgressSink? = null,
    /** 整个脚本的执行上限 */
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    /** 可恢复: 按"脚本指纹 + 调用序号"复用上一趟已完成的 agent 结果; null = 不复用 */
    private val journal: WorkflowJournal? = null,
    /** 同时在跑的 agent 数上限（由调用方按用户配的子代理并发传入） */
    private val agentConcurrency: Int = DEFAULT_AGENT_CONCURRENCY,
    private val maxAgents: Int = WorkflowLimits.MAX_AGENTS_PER_RUN,
    private val maxSchemaAttempts: Int = WorkflowLimits.MAX_SCHEMA_ATTEMPTS,
) {

    suspend fun execute(
        script: WorkflowScript,
        args: JsonElement = JsonObject(emptyMap()),
    ): WorkflowExecutionResult {
        val session = WorkflowCallSession(script.fingerprint)
        val gate = Semaphore(agentConcurrency.coerceAtLeast(1))
        return runCatching {
            val argsLiteral = JsonPrimitive(args.toString()).toString()
            val wrapped = WorkflowScriptPrelude.wrap(script.body, argsLiteral)

            val outcome = quickJs {
                installHostApi(session, gate)
                // 第一趟: 触发脚本; 宿主 async 绑定会在这一趟里跑完。
                // 注意这里**只跑一次** —— 若在轮询里反复求值整段脚本,
                // 脚本体（含它里面的 agent 调用）会被重复执行, 那是灾难性的。
                evaluate<String?>(wrapped)
                // 之后只读状态, 直到 await 之后的续体把结果写好
                awaitDone()
            }
            parseOutcome(outcome, session)
        }.getOrElse { error ->
            WorkflowExecutionResult(
                ok = false,
                resultJson = null,
                error = describe(error),
                agentsCalled = session.total,
                agentsReused = session.reused.get(),
            )
        }
    }

    private fun com.dokar.quickjs.QuickJs.installHostApi(session: WorkflowCallSession, gate: Semaphore) {
        function("__rhePhase") { args: Array<Any?> ->
            progress?.onPhase(args.getOrNull(0) as? String ?: "")
        }
        function("__rheLog") { args: Array<Any?> ->
            progress?.onLog(
                args.getOrNull(0) as? String ?: "",
                args.getOrNull(1) as? String ?: "log",
            )
        }
        // 位置参数（prompt, schemaJson, label）是宿主与 JS 适配层之间的约定;
        // 脚本看到的是上游的 agent(prompt, options) —— 拆选项在 WorkflowScriptPrelude 里做。
        asyncFunction<String?>("__rheAgent") { args: Array<Any?> ->
            val prompt = args.getOrNull(0) as? String ?: return@asyncFunction null
            val schemaJson = (args.getOrNull(1) as? String)?.takeIf { it.isNotBlank() }
            val label = (args.getOrNull(2) as? String)?.takeIf { it.isNotBlank() }
            runAgentCall(session, gate, prompt, schemaJson, label)
        }
    }

    /** 一次 agent 调用的完整语义: 序号 -> 复用 -> 排队 -> 结构化输出 -> 存档。 */
    private suspend fun runAgentCall(
        session: WorkflowCallSession,
        gate: Semaphore,
        prompt: String,
        schemaJson: String?,
        label: String?,
    ): String? {
        val index = session.nextIndex()
        if (index >= maxAgents) {
            throw WorkflowLimitException(
                "workflow exceeded " + maxAgents + " agent calls in one run"
            )
        }
        progress?.onAgentProgress(session.total, session.reused.get())

        recall(session, index, prompt, schemaJson, label)?.let { return it }

        val text = gate.withPermit { invokeAgent(index, prompt, schemaJson, label) }
        if (text == null) {
            // 被停止或不可恢复错误: 上游约定 agent() 返回 null, 且**不存档** ——
            // 下一趟重跑必须重做它（"still running when you stopped: starts over"）。
            session.stopReuseFrom(index)
            progress?.onLog("agent #" + index + " 没有产出（被停止或不可恢复错误）", "warn")
            return null
        }
        journal?.remember(
            session.fingerprint,
            WorkflowAgentRecord(index, prompt, schemaJson, label, text),
        )
        return text
    }

    /**
     * 尝试复用存档。
     *
     * 三条不复用的理由, 任何一条成立都要**从这个序号起**放弃复用（前缀式重放）:
     * 1. 存档里没有这个序号（上一趟没跑到这么远）;
     * 2. 提示词或 schema 与存档不一致（脚本改了, 或前面的结果变了）;
     * 3. 更早的调用已经失败过（resumeBarrier 已提前）。
     */
    private suspend fun recall(
        session: WorkflowCallSession,
        index: Int,
        prompt: String,
        schemaJson: String?,
        label: String?,
    ): String? {
        val journal = journal ?: return null
        if (index >= session.resumeBarrier) return null

        val record = journal.recall(session.fingerprint, index)
        if (record == null || record.prompt != prompt || record.schemaJson != schemaJson) {
            session.stopReuseFrom(index)
            return null
        }
        session.reused.incrementAndGet()
        progress?.onAgentProgress(session.total, session.reused.get())
        progress?.onLog(
            "agent #" + index + " 复用上一趟的结果" + if (label != null) "（" + label + "）" else "",
            "resume",
        )
        return record.resultText
    }

    /**
     * 真正调用子代理; 带 schema 时负责"附 schema -> 校验 -> 不合规重试"。
     *
     * 上游语义: schema 自相矛盾时**在启动子代理之前**就失败（别让它白跑）;
     * 输出连续 [maxSchemaAttempts] 次不合规则该次调用失败, 错误里带最后一次的失败原因。
     */
    private suspend fun invokeAgent(
        index: Int,
        prompt: String,
        schemaJson: String?,
        label: String?,
    ): String? {
        if (schemaJson == null) return agentHandler.run(prompt, null, label)

        val schema = WorkflowStructuredOutput.parseLoosely(schemaJson) as? JsonObject
        if (schema == null) {
            progress?.onLog("agent #" + index + " 的 schema 不是 JSON 对象, 跳过校验", "warn")
            return agentHandler.run(WorkflowStructuredOutput.structuredPrompt(prompt, schemaJson), null, label)
        }
        WorkflowStructuredOutput.checkSchema(schema)?.let { throw WorkflowSchemaException(it) }

        var lastFailure: String? = null
        var currentPrompt = WorkflowStructuredOutput.structuredPrompt(prompt, schemaJson)
        repeat(maxSchemaAttempts) { attempt ->
            val text = agentHandler.run(currentPrompt, schemaJson, label) ?: return null
            val parsed = WorkflowStructuredOutput.parseLoosely(text)
            val failure = if (parsed == null) {
                "输出不是合法 JSON"
            } else {
                WorkflowStructuredOutput.validate(schema, parsed)
            }
            if (failure == null) return parsed.toString()

            lastFailure = failure
            progress?.onLog(
                "agent #" + index + " 第 " + (attempt + 1) + " 次输出不合规: " + failure,
                "warn",
            )
            currentPrompt = WorkflowStructuredOutput.correctivePrompt(prompt, schemaJson, failure)
        }
        throw WorkflowSchemaException(
            "structured output failed after " + maxSchemaAttempts + " attempts; last failure: " + lastFailure
        )
    }

    /**
     * 轮询结果。
     *
     * 每趟都重新求值一次"是否完成": 宿主 job 与 await 续体在趟与趟之间推进,
     * 这正是实测到的语义（evaluate 返回时 await 之后的部分还没跑）。
     */
    private suspend fun com.dokar.quickjs.QuickJs.awaitDone(): String? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastSeen: String? = null
        while (System.currentTimeMillis() < deadline) {
            val state = evaluate<String?>(READ_STATE)
            if (state != null) return state
            // 每趟都留一份现场: 超时时能说清"卡在哪个状态", 而不是只报一句 timeout
            lastSeen = evaluate<String?>(DIAGNOSE) ?: lastSeen
            delay(POLL_INTERVAL_MS)
        }
        DIAGNOSTIC = lastSeen
        return null
    }

    private fun parseOutcome(outcome: String?, session: WorkflowCallSession): WorkflowExecutionResult {
        if (outcome == null) {
            // 超时信息里带上现场: 只说 "timed out" 对排查毫无帮助
            return WorkflowExecutionResult(
                ok = false,
                resultJson = null,
                error = "workflow timed out; last state = " + (DIAGNOSTIC ?: "<never read>"),
                agentsCalled = session.total,
                agentsReused = session.reused.get(),
            )
        }
        val obj = runCatching {
            JsonInstant.parseToJsonElement(outcome) as? JsonObject
        }.getOrNull() ?: return WorkflowExecutionResult(false, null, "unexpected runtime output")

        // 判定"有没有错"必须看**布尔标志**, 不能看 error 字段是不是 null。
        // 血的教训: JsonNull.content 返回的是**字面量字符串 "null"**（4 个字符）, 不是 Kotlin null。
        // 早先按 content != null 判定时, 每个**成功**的脚本都会被判成失败并给出 error="null";
        // 而失败信息打印出来正是 "应成功: null", 看起来像"error 是 null", 排查方向被彻底带偏。
        // JS 侧 throw null 也会产生字面量 "null", 靠字符串无法与真错误区分, 所以用标志位。
        val hasError = (obj["hasError"] as? JsonPrimitive)?.booleanOrNull == true
        if (hasError) {
            val message = (obj["error"] as? JsonPrimitive)?.contentOrNull
            return WorkflowExecutionResult(
                ok = false,
                resultJson = null,
                error = message ?: "workflow failed",
                agentsCalled = session.total,
                agentsReused = session.reused.get(),
            )
        }
        val hasResult = (obj["hasResult"] as? JsonPrimitive)?.booleanOrNull == true
        return WorkflowExecutionResult(
            ok = true,
            resultJson = if (hasResult) obj["result"]?.toString() else null,
            error = null,
            agentsCalled = session.total,
            agentsReused = session.reused.get(),
        )
    }

    /**
     * 失败原因要**永不落空**: 早先写成 message ?: javaClass.simpleName,
     * 而匿名类的 simpleName 是空串, 于是失败信息可能是一句空话。
     */
    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.java.name

    /** 超时诊断: 最后一次看到的状态（单元测试与真机排查都用它）。 */
    @Volatile
    var DIAGNOSTIC: String? = null
        private set

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60L * 60 * 1000
        const val POLL_INTERVAL_MS = 50L

        /** 默认的 agent 并发; 调用方一般会按用户设置覆盖它。 */
        const val DEFAULT_AGENT_CONCURRENCY = 2

        /** 超时时用: 把三个 globalThis 标志原样报出来。 */
        private val DIAGNOSE = listOf(
            "JSON.stringify({",
            "  done: globalThis.__rheDone,",
            "  hasResult: globalThis.__rheResult !== undefined && globalThis.__rheResult !== null,",
            "  result: globalThis.__rheResult === undefined ? \"<undef>\" : globalThis.__rheResult,",
            "  hasError: globalThis.__rheError !== undefined && globalThis.__rheError !== null,",
            "  error: globalThis.__rheError,",
            "  args: typeof globalThis.args,",
            "  agentType: typeof globalThis.agent",
            "})",
        ).joinToString("\n")

        /** 读回完成状态: 未完成返回 null, 完成返回一个带 hasResult/result/error 的 JSON 串。 */
        private val READ_STATE = listOf(
            "globalThis.__rheDone !== true ? null : JSON.stringify({",
            "  hasResult: globalThis.__rheResult !== undefined && globalThis.__rheResult !== null,",
            "  result: globalThis.__rheResult === undefined ? null : globalThis.__rheResult,",
            // hasError 必须是**真正的布尔**: 只有它能区分"没有错"与"错误信息恰好是字符串 null"
            "  hasError: globalThis.__rheError !== undefined && globalThis.__rheError !== null,",
            "  error: globalThis.__rheError === undefined ? null : globalThis.__rheError",
            "})",
        ).joinToString("\n")
    }
}
