package me.rerere.rikkahub.data.workflow

/**
 * 脚本运行时的硬性上限（对齐上游 behavior-and-limits）。
 *
 * 这些数字**不是**性能调优, 而是"失控保护": 一个脚本可以轻易写出
 * 每项再扇出 10 个 agent 的嵌套循环, 没有闸门就会把设备与额度一起烧掉。
 */
object WorkflowLimits {
    /** 单次 pipeline() / parallel() 的元素上限。上游同值。 */
    const val MAX_LIST_ITEMS = 4096

    /** 单次运行的 agent 调用总数上限。上游同值。 */
    const val MAX_AGENTS_PER_RUN = 1000

    /** 结构化输出不合规时的最大尝试次数。上游同值。 */
    const val MAX_SCHEMA_ATTEMPTS = 5
}

/**
 * 把脚本体包成"可被宿主反复求值读状态"的形态, 并注入宿主 API。
 *
 * 为什么需要包装（实测结论, 见 WorkflowRuntimeNotes）:
 * quickjs-kt 的 evaluate() **不解包 Promise**, 而宿主 async 绑定的协程会在 evaluate
 * 期间被推进、但 await 之后的 JS 续体要等下一次求值才执行。所以形态只能是:
 * "首趟跑一次脚本 -> 脚本把结果写进 globalThis -> 宿主再求值读回"。
 *
 * 这里注入的都是**纯 JS 适配层**, 刻意不把语义放进 Kotlin:
 * - agent(prompt, options) 是上游规约（一个 options 对象）, 拆成宿主的位置参数最自然;
 * - pipeline / parallel 用 Promise.all 表达, 天然保序、天然保留 null、天然并发;
 * - 并发**不在这里限流** —— 限流在宿主的 agent 闸门（按用户配的子代理配额）里做,
 *   这样无论脚本怎么扇出都拦得住。
 */
internal object WorkflowScriptPrelude {

    fun wrap(body: String, argsLiteral: String): String {
        val lines = listOf(
            "globalThis.args = JSON.parse($argsLiteral);",
            "globalThis.__rheDone = false; globalThis.__rheResult = null; globalThis.__rheError = null;",
            DETERMINISM_GUARD,
            HOST_API_ADAPTER,
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

    /**
     * 确定性护栏: Date.now() / Math.random() / 无参 new Date() 一律抛异常。
     *
     * 这是**可恢复性的前提**: 重跑必须产生同样的 agent 调用序列,
     * 否则"按调用序号复用已完成结果"会张冠李戴 —— 复用到一个本该不同的调用的结果,
     * 比不复用危险得多（脚本会拿到错的中间结果却毫不知情）。
     *
     * 用 class extends Date 而不是自定义构造器: 这样 Date.parse / Date.UTC /
     * instanceof Date 都还正常, 只掐掉"读当前时间"这三条路。
     */
    private val DETERMINISM_GUARD = listOf(
        "(function () {",
        "  const RealDate = Date;",
        "  class GuardedDate extends RealDate {",
        "    constructor(...args) {",
        "      if (args.length === 0) {",
        "        throw new Error(\"workflow: new Date() without arguments is not allowed — it breaks resumability. Pass a timestamp in through args instead.\");",
        "      }",
        "      super(...args);",
        "    }",
        "    static now() {",
        "      throw new Error(\"workflow: Date.now() is not allowed — it breaks resumability. Pass a timestamp in through args instead.\");",
        "    }",
        "  }",
        "  globalThis.Date = GuardedDate;",
        "  Math.random = function () {",
        "    throw new Error(\"workflow: Math.random() is not allowed — it breaks resumability.\");",
        "  };",
        "})();",
    ).joinToString("\n")

    /**
     * 宿主 API 的 JS 侧适配层。
     *
     * agent(prompt, options) —— 上游是**一个 options 对象**（{ schema, label }）:
     *   const audits = await agent(\"审计 \" + file, { label: file })
     *   await agent(\"...\", { schema: { type: \"object\" } })
     * 拆选项放在 JS 侧而不是 Kotlin 侧: Kotlin 侧读 args[1] 的字段就要依赖
     * quickjs-kt 把 JS 对象转成什么类型（实现细节）, 而 schema 的 JSON 化只有 JS 侧最方便。
     */
    private val HOST_API_ADAPTER = listOf(
        "globalThis.phase = globalThis.__rhePhase;",
        "globalThis.log = globalThis.__rheLog;",
        "globalThis.agent = function (prompt, options) {",
        "  options = options || {};",
        "  const schema = options.schema;",
        "  const schemaJson = (schema === undefined || schema === null) ? null : JSON.stringify(schema);",
        "  const label = options.label;",
        "  const pending = globalThis.__rheAgent(String(prompt), schemaJson, (label === undefined || label === null) ? null : String(label));",
        "  if (schemaJson === null) return pending;",
        // 带 schema 时脚本要的是**对象**, 不是一段 JSON 文本:
        //   const found = await agent(\"列出文件\", { schema: {...} });
        //   await pipeline(found.files, file => ...)
        // 宿主侧已经归一化成合法 JSON, 这里还原即可; 万一不是（schema 无法解析而退回散文）就原样返回,
        // 不要因为解析失败把一次本来可用的调用变成异常。
        "  return pending.then(function (text) {",
        "    if (text === null || text === undefined) return null;",
        "    try { return JSON.parse(text); } catch (e) { return text; }",
        "  });",
        "};",
        "globalThis.pipeline = function (items, fn) {",
        "  if (!Array.isArray(items)) {",
        "    throw new Error(\"pipeline(items, fn): items must be an array\");",
        "  }",
        "  if (typeof fn !== \"function\") {",
        "    throw new Error(\"pipeline(items, fn): fn must be a function\");",
        "  }",
        "  if (items.length > " + WorkflowLimits.MAX_LIST_ITEMS + ") {",
        "    throw new Error(\"pipeline: too many items (\" + items.length + \" > " + WorkflowLimits.MAX_LIST_ITEMS + "). Split the work into several calls.\");",
        "  }",
        "  return Promise.all(items.map(function (item, index) {",
        "    return Promise.resolve().then(function () { return fn(item, index); });",
        "  }));",
        "};",
        "globalThis.parallel = function (tasks) {",
        "  if (!Array.isArray(tasks)) {",
        "    throw new Error(\"parallel(tasks): tasks must be an array\");",
        "  }",
        "  if (tasks.length > " + WorkflowLimits.MAX_LIST_ITEMS + ") {",
        "    throw new Error(\"parallel: too many tasks (\" + tasks.length + \" > " + WorkflowLimits.MAX_LIST_ITEMS + "). Split the work into several calls.\");",
        "  }",
        "  return Promise.all(tasks.map(function (task) {",
        "    return Promise.resolve().then(function () {",
        "      return typeof task === \"function\" ? task() : task;",
        "    });",
        "  }));",
        "};",
    ).joinToString("\n")
}
