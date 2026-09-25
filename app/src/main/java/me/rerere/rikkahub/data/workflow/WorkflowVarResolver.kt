package me.rerere.rikkahub.data.workflow

import me.rerere.workspace.JobParamResolver

/**
 * 工作流的变量解析。
 *
 * 取值域比单个任务大一圈 —— 除了工作流参数, 还能引用**前面步骤的产物**:
 *
 * - `{{name}}`            工作流参数（声明式, 见 [WorkflowParam]）
 * - `{{steps.<id>.<key>}}` 前面步骤 export 出来的键
 * - `{{steps.<id>.exit_code}}` 步骤退出码（引擎自动导出, 不用声明）
 * - `{{name|raw}}`         原样插入, 不做 shell 转义（进阶用法, 同 JobParamResolver; 允许 `| raw` 带空格）
 *
 * 转义规则与 [JobParamResolver] 保持一致（POSIX 单引号转义）, 因为最终都是往 shell 里拼;
 * 未声明的占位符**保持原样并记录**, 不静默吃掉 —— 静默替换成空值会让"变量名打错"变得极难排查。
 */
object WorkflowVarResolver {

    /**
     * 占位符匹配。
     *
     * 两个细节都是踩过的坑:
     *
     * 1. **结尾的 } 必须转义成 \}\}**。孤立未转义的 `}` 在桌面 JVM 的 java.util.regex 里是容忍的,
     *    但 Android 的 Pattern 走 com.android.icu, 严格解析会抛 PatternSyntaxException。
     * 2. **用 by lazy 而不是直接初始化**。原先它是 object 的静态字段, 正则一非法就在 <clinit> 里抛异常,
     *    于是整个类此后永远加载失败 —— 第一次报 ExceptionInInitializerError, 之后每次调用都变成
     *    NoClassDefFoundError, 连不涉及变量的流程都跑不起来。改成 lazy 后单个调用失败, 不会毁掉整个类。
     *
     * 注意: JVM 单元测试**覆盖不到**第 1 条 —— 桌面的正则引擎本来就接受那种写法,
     * 所以"单测全绿"曾经和"真机必崩"同时成立。这类差异只能靠真机验证。
     */
    private val PLACEHOLDER: Regex by lazy {
        Regex("""\{\{\s*([A-Za-z0-9_.-]+)\s*(\|\s*raw)?\s*\}\}""")
    }

    data class Result(
        val text: String,
        /** 用到了但没值的占位符（变量名打错、上游步骤没导出该键等） */
        val unresolved: List<String>,
    ) {
        val isClean: Boolean get() = unresolved.isEmpty()
    }

    /** 引擎自动导出的步骤键（无需在 export 里声明）。 */
    fun builtinStepKeys(exitCode: Int?): Map<String, String> =
        buildMap {
            if (exitCode != null) put("exit_code", exitCode.toString())
        }

    /**
     * 渲染一段文本。
     *
     * @param params 工作流参数值（已按声明补过默认值）
     * @param stepOutputs 步骤 id -> (键 -> 值); 引擎自动导出的键请先并进去
     */
    fun render(
        text: String,
        params: Map<String, String>,
        stepOutputs: Map<String, Map<String, String>>,
    ): Result {
        val unresolved = mutableListOf<String>()
        // 兜底: 正则若在某运行时上无法编译（真机上确实发生过一次）, 这里退化为
        // "一个变量都没解析" 并把整段文本原样交给上层去报错 ——
        // 而不是让异常冒出去把整条流程打断。宁可"流程明确失败在变量上",
        // 也不要"流程莫名其妙跑不起来"。
        val pattern = runCatching { PLACEHOLDER }.getOrNull()
            ?: return Result(text, listOf("<placeholder pattern unavailable>"))
        val rendered = pattern.replace(text) { match ->
            val name = match.groupValues[1]
            val raw = match.groupValues[2].isNotBlank()
            val value = lookup(name, params, stepOutputs)
            if (value == null) {
                unresolved += name
                match.value
            } else {
                if (raw) value else JobParamResolver.shellQuote(value)
            }
        }
        return Result(rendered, unresolved.distinct())
    }

    private fun lookup(
        name: String,
        params: Map<String, String>,
        stepOutputs: Map<String, Map<String, String>>,
    ): String? {
        if (name.startsWith("steps.")) {
            val rest = name.removePrefix("steps.")
            val stepId = rest.substringBefore('.')
            val key = rest.substringAfter('.', "")
            if (key.isEmpty()) return null
            return stepOutputs[stepId]?.get(key)
        }
        return params[name]
    }

    /**
     * 把带默认值的参数固化成一次运行的实参。
     *
     * 缺必填参数会记录在 missing 里, 由调用方决定是拒绝启动还是照跑（工作流通常应当拒绝）。
     */
    fun resolveParams(
        declared: List<WorkflowParam>,
        args: Map<String, String>,
    ): Pair<Map<String, String>, List<String>> {
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
        // 未声明的实参也带上: 便于临时传值, 不因为没声明就丢掉
        args.forEach { (k, v) -> if (k !in values) values[k] = v }
        return values to missing
    }
}
