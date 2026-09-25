package me.rerere.rikkahub.data.workflow

import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.delay
import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

/** 运行时的进度回调（阶段 / 日志）。 */
interface WorkflowProgressSink {
    fun onPhase(title: String)
    fun onLog(message: String, level: String)
}

/** 一次脚本执行的结果。 */
data class WorkflowExecutionResult(
    val ok: Boolean,
    /** 脚本 `return` 的值（JSON 文本）; 失败时为 null */
    val resultJson: String?,
    /** 失败原因 */
    val error: String?,
)

/**
 * workflow 脚本的运行时。
 *
 * 桥接方式基于实测（见 WorkflowRuntimeNotes）:
 * - 宿主 async 绑定**会在 evaluate 期间自行跑完**（实测 invoked=1 completed=1）;
 * - 但 evaluate 会在"await 之后的 JS 续体"执行前返回（实测返回 <pending>）;
 * - 所以: 脚本把结果写进 globalThis, 宿主**再求值一次**即可读到 —— 不需要自己搭事件循环。
 *
 * 脚本内禁掉 Date.now() / Math.random() / 无参 new Date():
 * 可恢复性依赖"重跑产生同样的 agent 调用", 时间与随机数会破坏这个前提。
 */
class WorkflowRuntime(
    private val agentHandler: WorkflowAgentHandler,
    private val progress: WorkflowProgressSink? = null,
    /** 整个脚本的执行上限 */
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
) {

    suspend fun execute(
        script: WorkflowScript,
        args: Map<String, String> = emptyMap(),
    ): WorkflowExecutionResult = runCatching {
        val argsJson = JsonInstant.encodeToString(args)
        val argsLiteral = JsonPrimitive(argsJson).toString()
        val wrapped = wrap(script.body, argsLiteral)

        val outcome = quickJs {
            installHostApi()
            // 第一趟: 触发脚本; 宿主 async 绑定会在这一趟里跑完。
            // 注意这里**只跑一次** —— 若在轮询里反复求值整段脚本,
            // 脚本体（含它里面的 agent 调用）会被重复执行, 那是灾难性的。
            evaluate<String?>(wrapped)
            // 之后只读状态, 直到 await 之后的续体把结果写好
            awaitDone()
        }
        parseOutcome(outcome)
    }.getOrElse { error ->
        WorkflowExecutionResult(false, null, error.message ?: error::class.java.simpleName)
    }

    private fun com.dokar.quickjs.QuickJs.installHostApi() {
        function("__rhePhase") { args: Array<Any?> ->
            progress?.onPhase(args.getOrNull(0) as? String ?: "")
        }
        function("__rheLog") { args: Array<Any?> ->
            progress?.onLog(
                args.getOrNull(0) as? String ?: "",
                args.getOrNull(1) as? String ?: "log",
            )
        }
        asyncFunction<String?>("__rheAgent") { args: Array<Any?> ->
            val prompt = args.getOrNull(0) as? String ?: return@asyncFunction null
            val schemaJson = args.getOrNull(1) as? String
            val label = args.getOrNull(2) as? String
            agentHandler.run(prompt, schemaJson, label)
        }
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

    private fun parseOutcome(outcome: String?): WorkflowExecutionResult {
        if (outcome == null) {
            // 超时信息里带上现场: 只说 "timed out" 对排查毫无帮助
            return WorkflowExecutionResult(
                ok = false,
                resultJson = null,
                error = "workflow timed out; last state = " + (DIAGNOSTIC ?: "<never read>"),
            )
        }
        val obj = runCatching {
            JsonInstant.parseToJsonElement(outcome) as? JsonObject
        }.getOrNull() ?: return WorkflowExecutionResult(false, null, "unexpected runtime output")
        val error = obj["error"]?.jsonPrimitive?.contentOrNullSafe()
        if (error != null) return WorkflowExecutionResult(false, null, error)
        val hasResult = obj["hasResult"]?.jsonPrimitive?.content == "true"
        return WorkflowExecutionResult(
            ok = true,
            resultJson = if (hasResult) obj["result"]?.toString() else null,
            error = null,
        )
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { this.content }.getOrNull()

    private fun wrap(body: String, argsLiteral: String): String {
        val lines = listOf(
            "globalThis.args = JSON.parse($argsLiteral);",
            "globalThis.__rheDone = false; globalThis.__rheResult = null; globalThis.__rheError = null;",
            "globalThis.phase = globalThis.__rhePhase;",
            "globalThis.log = globalThis.__rheLog;",
            "globalThis.agent = globalThis.__rheAgent;",
            "(async () => {",
            "  try {",
            "    globalThis.__rheResult = await (async () => {",
            "      " + body.trim(),
            "    })();",
            "  } catch (e) {",
            "    globalThis.__rheError = String((e && e.message) || e);",
            "  } finally {",
            "    globalThis.__rheDone = true;",
            "  }",
            "})();",
            "null",
        )
        return lines.joinToString("\n")
    }

    /** 超时诊断: 最后一次看到的状态（单元测试与真机排查都用它）。 */
    @Volatile
    var DIAGNOSTIC: String? = null
        private set

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60L * 60 * 1000
        const val POLL_INTERVAL_MS = 50L

        /** 超时时用: 把三个 globalThis 标志原样报出来。 */
        private val DIAGNOSE = listOf(
            "JSON.stringify({",
            "  done: globalThis.__rheDone,",
            "  hasResult: globalThis.__rheResult !== undefined && globalThis.__rheResult !== null,",
            "  result: globalThis.__rheResult === undefined ? \"<undef>\" : globalThis.__rheResult,",
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
            "  error: globalThis.__rheError === null ? null : globalThis.__rheError",
            "})",
        ).joinToString("\n")
    }
}
