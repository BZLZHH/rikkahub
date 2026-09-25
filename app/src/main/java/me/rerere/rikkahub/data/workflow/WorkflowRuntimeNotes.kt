package me.rerere.rikkahub.data.workflow

/**
 * workflow 运行时的技术笔记 —— 这些结论是**在 JVM 单测里对 quickjs-kt 实测**得来的,
 * 不是从文档推断的, 所以写在这里免得重踩。
 *
 * ## 好消息: JVM 单测能跑 QuickJS
 * app/build.gradle.kts 里为 UnitTestRuntimeClasspath 做了依赖替换
 * （quickjs-kt-android -> quickjs-kt-jvm）, 而 jvm 包内含 jni/linux_x64/libquickjs.so。
 * 也就是说**运行时桥接是可测的** —— 这一点很重要, 因为本项目已经吃过"单测全绿但真机崩"的亏
 * （桌面 JVM 的正则引擎容忍未转义的 }, Android 的 ICU 严格解析直接抛异常）。
 * 凡是能在 JVM 测里覆盖的, 就不要留到真机才发现。
 *
 * ## 异步语义（实测）
 * - 宿主用 `asyncFunction(name) { args -> ... }` 注入的函数, 在 JS 侧是 **Promise**;
 * - **`evaluate()` 不会自动解包 Promise**:
 *   - `evaluate("hostAsync()")`            -> `Promise { <state>: "fulfilled" }`
 *   - `evaluate("(async () => await hostAsync())()")` -> **同样是 Promise**
 *     即"脚本内 await"并不能让 evaluate 直接给到值。
 * - 库内部有完整的等待机制（`evalAndAwait` / `awaitEvaluateResult` 轮询 progress、
 *   `asyncJobs`、`jobsMutex`、`invokeAsyncFunction$job`）, 但这些是 **internal**, 公开 API 只有 `evaluate`。
 *
 * ## 因此运行时必须自己搭一处桥
 * 计划（下一轮据此实现）:
 * 1. 用 quickJs(ModuleLoader) 把脚本作为 ESM 模块加载（原生支持顶层 await 语法）;
 * 2. 注入 `agent` / `pipeline` / `phase` / `log` 与 `args` 全局;
 * 3. 脚本外包一层: 把最终结果写进 `globalThis.__rheResult` 并置 `__rheDone = true`;
 * 4. Kotlin 侧先 `evaluate` 一次拿"是否已 done"; 未 done 则**在 evaluate 之外**等待即可完成的部分
 *    —— 关键点是宿主 async 绑定的协程必须跑在 evaluate 之外, 否则任务队列无法推进;
 * 5. 循环重求值 `__rheDone` 直到 true 或超时。
 *
 * ## 另一条必须照做的约定（来自上游文档）
 * 脚本内 `Date.now()` / `Math.random()` / 无参 `new Date()` 必须**抛异常**:
 * 可恢复性依赖"重跑产生同样的 agent 调用", 时间与随机数会让重跑的调用序列不同,
 * 于是已完成的结果无法安全复用。
 */
internal object WorkflowRuntimeNotes
