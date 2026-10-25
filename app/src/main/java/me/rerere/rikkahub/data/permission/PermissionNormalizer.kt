package me.rerere.rikkahub.data.permission

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.ArrayDeque
import java.util.UUID

/**
 * 把一次工具调用翻译成"它到底要干什么"：效果 + 具体目标 + 一句人话摘要。
 *
 * 规则匹配、审计、同意卡片都只看这个结构，不看原始 JSON —— 原始参数是可以被
 * 拼接、截断、伪装的，直接对字符串做匹配就是绕过面。
 */
object PermissionNormalizer {

    /** 与 WorkspaceTools 里历史的安全区保持一致：这里写文件不需要额外同意。 */
    private val WRITABLE_ROOTS = listOf("/workspace", "/tmp", "/skills")

    private val NONE_TOOLS = setOf(
        "get_time_info", "get_screen_time", "text_to_speech", "calendar_query",
        "conversation_search", "recent_chats", "memory_tool", "use_skill",
    )

    fun effectsOf(tool: String): Set<ToolEffect> = when {
        tool == "workspace_read_file" -> setOf(ToolEffect.FS_READ)
        tool == "workspace_write_file" || tool == "workspace_edit_file" -> setOf(ToolEffect.FS_WRITE)
        tool == "workspace_shell" -> setOf(ToolEffect.PROC_EXEC)
        tool == "job_start" || tool == "job_run" -> setOf(ToolEffect.DELEGATE, ToolEffect.PROC_EXEC)
        tool == "job_def_create" || tool == "job_def_update" -> setOf(ToolEffect.DELEGATE)
        tool.startsWith("job_") -> setOf(ToolEffect.PROC_CONTROL)
        tool == "subagent_start" -> setOf(ToolEffect.DELEGATE)
        tool.startsWith("subagent_") -> setOf(ToolEffect.PROC_CONTROL)
        tool == "workflow_run" -> setOf(ToolEffect.DELEGATE)
        tool.startsWith("workflow_") -> setOf(ToolEffect.PROC_CONTROL)
        tool.startsWith("mcp__") -> setOf(ToolEffect.EXTERNAL)
        tool == "search_web" || tool == "scrape_web" -> setOf(ToolEffect.NET)
        tool == "ask_user" -> setOf(ToolEffect.HUMAN)
        tool == "calendar_create" -> setOf(ToolEffect.DEVICE)
        tool == "clipboard_tool" -> setOf(ToolEffect.DEVICE)
        tool == "eval_javascript" -> setOf(ToolEffect.PROC_EXEC)
        tool in NONE_TOOLS -> setOf(ToolEffect.NONE)
        else -> setOf(ToolEffect.UNKNOWN)
    }

    fun normalize(tool: String, args: JsonElement): NormalizedCall {
        val obj = args as? JsonObject ?: JsonObject(emptyMap())
        val effects = effectsOf(tool)
        return when {
            tool == "workspace_shell" -> normalizeShell(tool, obj, effects)
            tool == "workspace_read_file" -> normalizePath(tool, obj, write = false)
            tool == "workspace_write_file" || tool == "workspace_edit_file" ->
                normalizePath(tool, obj, write = true)
            tool == "job_start" || tool == "job_run" -> normalizeJob(tool, obj, effects)
            tool == "job_def_create" || tool == "job_def_update" -> normalizeJobDef(tool, obj, effects)
            tool == "subagent_start" -> normalizeSubagent(obj, effects)
            tool == "workflow_run" -> normalizeWorkflow(obj, effects)
            tool.startsWith("mcp__") -> NormalizedCall(
                tool = tool,
                effects = effects,
                summary = "调用外部服务：" + tool,
            )
            else -> NormalizedCall(
                tool = tool,
                effects = effects,
                summary = simpleSummary(tool),
            )
        }
    }

    /** 用户点"以后都这样"时，把这次调用概括成一条规则；概括不了就返回 null。 */
    fun propose(call: NormalizedCall): PermissionRule? {
        if (call.opaque) return null
        if (call.effects.contains(ToolEffect.HUMAN)) return null
        if (call.effects.contains(ToolEffect.UNKNOWN)) return null
        if (call.effects == setOf(ToolEffect.NONE)) return null
        val now = System.currentTimeMillis()

        val execs = call.targets.filterIsInstance<CallTarget.Exec>()
        val isDelegation = call.effects.contains(ToolEffect.DELEGATE)
        if (execs.isNotEmpty() && !isDelegation) {
            val binaries = execs.flatMap { it.segments }.mapNotNull { it.firstOrNull() }.toSet()
            if (binaries.size == 1) {
                return PermissionRule(
                    id = UUID.randomUUID().toString(),
                    decision = RuleDecision.ALLOW,
                    tool = call.tool,
                    effect = ToolEffect.PROC_EXEC,
                    commandPrefix = binaries.first(),
                    createdAt = now,
                )
            }
        }

        val fs = call.targets.filterIsInstance<CallTarget.Fs>().firstOrNull { it.write }
        if (fs != null) {
            val parent = fs.path.substringBeforeLast('/', "") + "/"
            return PermissionRule(
                id = UUID.randomUUID().toString(),
                decision = RuleDecision.ALLOW,
                tool = call.tool,
                effect = ToolEffect.FS_WRITE,
                pathPrefix = parent.ifBlank { "/workspace/" },
                createdAt = now,
            )
        }

        val effect = when {
            call.effects.contains(ToolEffect.DELEGATE) -> ToolEffect.DELEGATE
            call.effects.contains(ToolEffect.EXTERNAL) -> ToolEffect.EXTERNAL
            call.effects.contains(ToolEffect.DEVICE) -> ToolEffect.DEVICE
            call.effects.contains(ToolEffect.PROC_EXEC) -> ToolEffect.PROC_EXEC
            call.effects.contains(ToolEffect.FS_WRITE) -> ToolEffect.FS_WRITE
            call.effects.contains(ToolEffect.FS_DELETE) -> ToolEffect.FS_DELETE
            else -> null
        } ?: return null

        return PermissionRule(
            id = UUID.randomUUID().toString(),
            decision = RuleDecision.ALLOW,
            tool = call.tool,
            effect = effect,
            createdAt = now,
        )
    }

    // ------------------------------------------------------------ 具体工具

    private fun normalizeShell(
        tool: String,
        obj: JsonObject,
        effects: Set<ToolEffect>,
    ): NormalizedCall {
        val command = obj.string("command")
        val parsed = parseShell(command)
        return NormalizedCall(
            tool = tool,
            effects = effects,
            targets = if (parsed.segments.isEmpty()) emptyList() else listOf(CallTarget.Exec(parsed.segments)),
            opaque = parsed.opaque || command.isBlank(),
            summary = shellSummary(command, parsed),
        )
    }

    private fun normalizePath(tool: String, obj: JsonObject, write: Boolean): NormalizedCall {
        val raw = obj.string("path")
        val path = normalizePathString(raw)
        val writable = WRITABLE_ROOTS.any { path == it || path.startsWith(it + "/") }
        return NormalizedCall(
            tool = tool,
            effects = setOf(if (write) ToolEffect.FS_WRITE else ToolEffect.FS_READ),
            targets = listOf(CallTarget.Fs(path, write)),
            opaque = path.isBlank() || (write && !writable),
            summary = (if (write) "写入 " else "读取 ") + path.ifBlank { "（未提供路径）" },
        )
    }

    private fun normalizeJob(
        tool: String,
        obj: JsonObject,
        effects: Set<ToolEffect>,
    ): NormalizedCall {
        val command = obj.string("command")
        val parsed = parseShell(command)
        val seconds = obj.long("max_runtime_seconds")?.coerceIn(60L, 86_400L) ?: 21_600L
        val minutes = seconds / 60
        val title = if (tool == "job_run") "运行后台任务" else "启动后台任务"
        val summary = if (command.isBlank()) {
            title + "（最长 " + minutes + " 分钟）"
        } else {
            title + "：" + firstLine(command) + "（最长 " + minutes + " 分钟）"
        }
        return NormalizedCall(
            tool = tool,
            effects = effects,
            targets = if (command.isBlank()) emptyList() else listOf(CallTarget.Exec(parsed.segments)),
            opaque = parsed.opaque,
            summary = summary,
            delegation = DelegationSummary(
                title = title,
                grants = listOf("执行任意命令（含联网）", "读写工作区", "最长 " + minutes + " 分钟"),
                excludes = listOf("启动子任务"),
                durationSeconds = seconds,
            ),
        )
    }

    private fun normalizeJobDef(
        tool: String,
        obj: JsonObject,
        effects: Set<ToolEffect>,
    ): NormalizedCall {
        val name = obj.string("name").ifBlank { "定时任务" }
        return NormalizedCall(
            tool = tool,
            effects = effects,
            summary = "登记定时任务：" + name,
            delegation = DelegationSummary(
                title = name,
                grants = listOf("到点自动执行命令", "读写工作区", "可联网"),
                excludes = listOf("立即执行"),
            ),
        )
    }

    private fun normalizeSubagent(obj: JsonObject, effects: Set<ToolEffect>): NormalizedCall {
        val title = obj.string("title").ifBlank { "子代理" }
        val groups = (obj["tool_groups"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        val grants = buildList {
            add("工作区读写")
            add("执行命令")
            if (groups.any { it.equals("web", ignoreCase = true) }) add("联网检索")
            if (groups.any { it.equals("local", ignoreCase = true) }) add("本机能力（日历/剪贴板/TTS）")
            if (groups.any { it.equals("memory", ignoreCase = true) }) add("读取记忆")
            if (groups.any { it.equals("conversation", ignoreCase = true) }) add("参考历史会话")
            if (groups.any { it.equals("skill", ignoreCase = true) }) add("使用技能")
        }
        return NormalizedCall(
            tool = "subagent_start",
            effects = effects,
            summary = "派生子代理：" + title,
            delegation = DelegationSummary(
                title = title,
                grants = grants,
                excludes = listOf("再派生子代理", "启动后台任务"),
            ),
        )
    }

    private fun normalizeWorkflow(obj: JsonObject, effects: Set<ToolEffect>): NormalizedCall {
        val title = obj.string("title").ifBlank { obj.string("name").ifBlank { "工作流" } }
        return NormalizedCall(
            tool = "workflow_run",
            effects = effects,
            summary = "运行工作流：" + title,
            delegation = DelegationSummary(
                title = title,
                grants = listOf("编排多个子代理", "每个子代理：工作区读写 + 执行命令", "可联网"),
                excludes = listOf("递归派生子代理 / 工作流"),
            ),
        )
    }

    private fun simpleSummary(tool: String): String = when (tool) {
        "calendar_create" -> "创建日历事件"
        "calendar_query" -> "查询日历"
        "clipboard_tool" -> "读写剪贴板"
        "text_to_speech" -> "朗读文本"
        "get_screen_time" -> "读取屏幕使用时间"
        "get_time_info" -> "读取当前时间"
        "eval_javascript" -> "执行 JavaScript 片段"
        "ask_user" -> "向你提问"
        "search_web" -> "联网搜索"
        "scrape_web" -> "抓取网页"
        "conversation_search" -> "检索历史会话"
        "recent_chats" -> "参考历史会话"
        "memory_tool" -> "读写记忆"
        "use_skill" -> "使用技能"
        else -> tool
    }

    // ------------------------------------------------------------ shell 解析

    private data class ParsedShell(val segments: List<List<String>>, val opaque: Boolean)

    private fun parseShell(command: String): ParsedShell {
        if (command.isBlank()) return ParsedShell(emptyList(), true)
        val segments = splitSegments(command).map { tokenize(it) }.filter { it.isNotEmpty() }
        return ParsedShell(segments, isOpaque(command) || segments.isEmpty())
    }

    private fun splitSegments(command: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (c in command) {
            if (escaped) {
                current.append(c)
                escaped = false
                continue
            }
            if (c == '\\') {
                current.append(c)
                escaped = true
                continue
            }
            if (quote != null) {
                current.append(c)
                if (c == quote) quote = null
                continue
            }
            when (c) {
                '\'', '"' -> {
                    quote = c
                    current.append(c)
                }
                ';', '\n', '|', '&' -> {
                    out += current.toString()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
        }
        out += current.toString()
        return out.map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun tokenize(segment: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (c in segment) {
            if (escaped) {
                sb.append(c)
                escaped = false
                continue
            }
            if (quote != null) {
                if (c == quote) {
                    quote = null
                } else if (c == '\\') {
                    escaped = true
                } else {
                    sb.append(c)
                }
                continue
            }
            when {
                c == '\'' || c == '"' -> quote = c
                c == '\\' -> escaped = true
                c == ' ' || c == '\t' -> {
                    if (sb.isNotEmpty()) {
                        out += sb.toString()
                        sb.setLength(0)
                    }
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun isOpaque(command: String): Boolean {
        if (command.contains(96.toChar())) return true
        if (command.contains("$(")) return true
        if (command.contains("<<")) return true
        val lower = command.lowercase()
        val markers = listOf(
            "eval ", "sudo ", " su ", "|sh", "| sh", "|bash", "| bash",
            "sh -c", "bash -c", "base64 -d", "/dev/tcp",
        )
        return markers.any { lower.contains(it) }
    }

    private fun shellSummary(command: String, parsed: ParsedShell): String {
        val line = command.trim().lineSequence().firstOrNull().orEmpty()
        val head = if (line.length > 70) line.take(70) + "…" else line
        val count = parsed.segments.size
        return if (count > 1) "执行：" + head + "（共 " + count + " 段）" else "执行：" + head
    }

    private fun firstLine(command: String): String {
        val line = command.trim().lineSequence().firstOrNull().orEmpty()
        return if (line.length > 60) line.take(60) + "…" else line
    }

    private fun normalizePathString(raw: String): String {
        if (raw.isBlank()) return ""
        val unified = raw.replace('\\', '/')
        val absolute = unified.startsWith("/")
        val stack = ArrayDeque<String>()
        for (part in unified.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        val joined = stack.joinToString("/")
        return if (absolute) "/" + joined else joined
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.let { it.intOrNull?.toLong() }
}
