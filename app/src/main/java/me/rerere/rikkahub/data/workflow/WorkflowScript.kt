package me.rerere.rikkahub.data.workflow

import kotlinx.serialization.Serializable

/**
 * 工作流脚本的元信息（对应脚本里的 `export const meta`）。
 *
 * 文档约定: `meta` 必须是脚本的**第一个语句**, 且只能是字面量（不能有变量、函数调用、展开），
 * 否则该工作流不能按名调用。这里保留同样约束 —— 解析不了就降级为"只能内联运行"。
 */
@Serializable
data class WorkflowMeta(
    val name: String,
    val description: String = "",
    /** 声明的阶段标题; 脚本里 phase() 用了这里没列的标题时, 该阶段仍会出现在进度里 */
    val phases: List<String> = emptyList(),
)

/** 解析好的工作流脚本。 */
data class WorkflowScript(
    val meta: WorkflowMeta?,
    /** 剥掉 meta 声明后的模块源码（可执行部分） */
    val body: String,
    /** 归一化后的脚本指纹: 供"可恢复"判断脚本是否变过 */
    val fingerprint: String,
) {
    val displayName: String get() = meta?.name?.takeIf { it.isNotBlank() } ?: "(inline workflow)"
}

/**
 * 从脚本文本里解析 `export const meta = { ... }`。
 *
 * 刻意**不用 JS 引擎**来解析 meta: meta 必须是纯字面量, 用不着求值;
 * 而且这样在脚本本身有语法错误时, 我们仍能报出"meta 长什么样" ——
 * 比"整个求值失败"更有指向性。
 */
object WorkflowScriptParser {

    /**
     * 匹配到**第一个顶格的 }** 为止（meta 对象自己的收尾括号）。
     * 不能简单用"第一个换行后的 }" —— meta 里嵌套对象（如 phases）的收尾括号
     * 若恰好落在行首, 会被提前截断, 于是 phases 解析成空。
     */
    private val META_PATTERN = Regex("""export\s+const\s+meta\s*=\s*(\{[\s\S]*?\n[ \t]*\})""")
    private val NAME_PATTERN = Regex("""name\s*:\s*['"]([^'"]+)['"]""")
    private val DESCRIPTION_PATTERN = Regex("""description\s*:\s*['"]([^'"]*)['"]""")
    private val PHASES_PATTERN = Regex("""phases\s*:\s*\[([\s\S]*?)\]""")
    private val STRING_LITERAL = Regex("""['"]([^'"]*)['"]""")

    fun parse(source: String): WorkflowScript {
        val match = META_PATTERN.find(source)
        val meta = match?.let { parseMeta(it.groupValues[1]) }
        val body = if (match != null) source.removeRange(match.range) else source
        return WorkflowScript(
            meta = meta,
            body = body.trimStart(),
            fingerprint = fingerprintOf(source),
        )
    }

    private fun parseMeta(literal: String): WorkflowMeta? {
        val name = NAME_PATTERN.find(literal)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: return null
        val description = DESCRIPTION_PATTERN.find(literal)?.groupValues?.get(1).orEmpty()
        val phases = PHASES_PATTERN.find(literal)?.groupValues?.get(1)
            ?.let { inner -> STRING_LITERAL.findAll(inner).map { it.groupValues[1] }.toList() }
            .orEmpty()
        return WorkflowMeta(name = name, description = description, phases = phases)
    }

    /**
     * 指纹只关心**语义内容**, 不关心空白差异 ——
     * 否则"重排一下缩进"就会让已完成的结果全部失效, 可恢复性形同虚设。
     */
    private fun fingerprintOf(source: String): String {
        val normalized = source.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        return sha256(normalized)
    }

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b) }
}
