package me.rerere.workspace

/**
 * 任务参数定义（模板占位符 / 环境变量）。
 */
data class JobParamDef(
    val name: String,
    val required: Boolean = false,
    val default: String? = null,
    val description: String? = null,
)

data class ResolvedJobParams(
    val command: String,
    val env: Map<String, String>,
    val missing: List<String>,
    val unresolvedPlaceholders: List<String>,
)

/**
 * 把 {{name}} 占位符与 RIKKA_PARAM_* 环境变量解析进命令文本。
 *
 * - {{name}} 默认按 shell 单引号转义后替换（安全），{{name|raw}} 原样插入（进阶用法）。
 * - 未声明的占位符保持原样并记录在 [ResolvedJobParams.unresolvedPlaceholders]，不静默吃掉。
 */
object JobParamResolver {
    private val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z0-9_-]+)\s*(\|\s*raw)?\s*\}\}""")

    fun envName(name: String): String =
        "RIKKA_PARAM_" + name.uppercase().map { c -> if (c.isLetterOrDigit()) c else '_' }.joinToString("")

    /** POSIX 单引号转义: 把内部的 ' 变成 '\'' */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun resolve(
        command: String,
        declared: List<JobParamDef>,
        args: Map<String, String>,
    ): ResolvedJobParams {
        val values = LinkedHashMap<String, String>()
        val missing = mutableListOf<String>()
        declared.forEach { def ->
            val provided = args[def.name] ?: def.default
            if (provided == null) {
                if (def.required) missing += def.name
            } else {
                values[def.name] = provided
            }
        }

        val unresolved = mutableListOf<String>()
        val resolvedCommand = PLACEHOLDER.replace(command) { match ->
            val name = match.groupValues[1]
            val raw = match.groupValues[2].isNotBlank()
            val value = values[name]
            when {
                value == null -> {
                    unresolved += name
                    match.value
                }

                raw -> value
                else -> shellQuote(value)
            }
        }

        val env = values.mapKeys { (name, _) -> envName(name) }
        return ResolvedJobParams(
            command = resolvedCommand,
            env = env,
            missing = missing,
            unresolvedPlaceholders = unresolved.distinct(),
        )
    }
}
