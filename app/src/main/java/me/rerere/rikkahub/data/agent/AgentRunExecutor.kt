package me.rerere.rikkahub.data.agent

import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.db.dao.AgentRunDAO
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.run.ActiveRun
import me.rerere.rikkahub.data.run.RunExecutor
import me.rerere.rikkahub.data.run.RunHandle
import me.rerere.rikkahub.data.run.RunKind
import me.rerere.rikkahub.data.run.RunLaunchResult
import me.rerere.rikkahub.data.run.RunRegistry
import me.rerere.rikkahub.data.run.RunStatus
import me.rerere.rikkahub.utils.JsonInstant
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "AgentRunExecutor"

/**
 * 子代理的执行体: 把 [AgentRunner] 接进编排层。
 *
 * 与 shell 执行体结构对称（见 ShellRunExecutor）, 但"终止"的含义完全不同:
 * shell 要杀进程树, 子代理只需**取消协程** —— 这正是把编排层与执行体分开的理由。
 */
class AgentRunExecutor(
    /**
     * 依赖具体类型 AppScope 而不是 CoroutineScope —— DI 里是单类型注册, 用抽象 get() 会失败。
     * 这条路径是**懒加载**的（subagent_start 才构造）, 所以配错不会在启动时崩,
     * 而是在用户第一次派子代理时才失败 —— 更难发现, 所以更要按类型写对。
     */
    private val scope: AppScope,
    private val dao: AgentRunDAO,
    private val runner: AgentRunner,
    private val toolFactory: AgentToolFactory,
    private val settingsStore: SettingsStore,
    private val workspaceRepository: WorkspaceRepository,
    private val transcripts: AgentTranscriptStore,
) : RunExecutor {

    override val kind: RunKind = RunKind.AGENT

    /** runId -> 正在跑的协程; 没有协程条目意味着已经结束。 */
    private val jobs = ConcurrentHashMap<String, Job>()

    override suspend fun launch(active: ActiveRun, registry: RunRegistry): RunLaunchResult {
        val run = dao.getById(active.record.runId)
            ?: return RunLaunchResult.NotStarted("agent run row not found: " + active.record.runId)
        val workspace = workspaceRepository.getById(run.workspaceId)
            ?: return RunLaunchResult.NotStarted("workspace not found: " + run.workspaceId)

        val settings = settingsStore.settingsFlowRaw.first()
        val parentAssistant = settings.assistants.firstOrNull {
            it.id.toString() == run.assistantId
        } ?: settings.assistants.firstOrNull()
            ?: return RunLaunchResult.NotStarted("no assistant available to fork")

        val args = runCatching {
            JsonInstant.parseToJsonElement(run.argsJson) as? JsonObject
        }.getOrNull().orEmpty()
        val cwd = args["cwd"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val maxSteps = args["max_steps"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_STEPS
        val excludeJobTools = args["exclude_job_tools"]?.jsonPrimitive?.contentOrNull == "true"

        // 模型: 显式指定优先, 否则回退"快速模型" —— 子代理是内部劳动力, 不该占用主模型
        val model = settings.findModelById(
            run.modelId?.let { runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull() },
            settings.fastModelId,
        ) ?: return RunLaunchResult.NotStarted("no model available for subagent")

        val groups = AgentToolGroup.parseAll(
            runCatching {
                JsonInstant.decodeFromString<List<String>>(run.toolGroupsJson)
            }.getOrDefault(emptyList())
        ).ifEmpty { setOf(AgentToolGroup.WORKSPACE) }

        val tools = toolFactory.createTools(
            settings = settings,
            assistant = parentAssistant,
            model = model,
            groups = groups,
            excludeJobTools = excludeJobTools,
            cwd = cwd,
            conversationId = run.conversationId,
        )

        val startedAt = System.currentTimeMillis()
        dao.upsert(run.copy(status = RunStatus.RUNNING.name, startedAt = startedAt, error = null))

        val instructions = buildInstructions(run.title, groups, excludeJobTools)
        val job = scope.launch {
            runCatching {
                runner.run(
                    runId = run.id,
                    settings = settings,
                    model = model,
                    parentAssistant = parentAssistant,
                    tools = tools,
                    instructions = instructions,
                    prompt = run.prompt,
                    cwd = cwd,
                    maxSteps = maxSteps,
                ).collect { event ->
                    when (event) {
                        is AgentEvent.Messages -> {
                            // 转录已由 AgentRunner 增量落盘; 这里占用行数做游标即可
                        }
                        is AgentEvent.Progress -> {
                            dao.getById(run.id)?.let { row ->
                                dao.upsert(
                                    row.copy(
                                        stepsDone = event.steps,
                                        toolCalls = event.toolCalls,
                                        tokensIn = event.promptTokens,
                                        tokensOut = event.completionTokens,
                                    )
                                )
                            }
                        }
                        is AgentEvent.Finished -> {
                            dao.getById(run.id)?.let { row ->
                                val finishedAt = System.currentTimeMillis()
                                dao.upsert(
                                    row.copy(
                                        status = RunStatus.SUCCEEDED.name,
                                        // 全文由转录负责（AgentRunner 落盘）;
                                        // 结果列只放"够父代理判断"的元信息 + 一段预览, 避免行被撑爆。
                                        resultJson = JsonInstant.encodeToString(
                                            kotlinx.serialization.json.JsonObject(
                                                buildMap {
                                                    put(
                                                        "textLength",
                                                        kotlinx.serialization.json.JsonPrimitive(
                                                            event.text.length
                                                        ),
                                                    )
                                                    put(
                                                        "preview",
                                                        kotlinx.serialization.json.JsonPrimitive(
                                                            event.text.take(2_000)
                                                        ),
                                                    )
                                                }
                                            )
                                        ),
                                        finishedAt = finishedAt,
                                        runtimeMs = finishedAt - (row.startedAt ?: finishedAt),
                                    )
                                )
                            }
                        }
                    }
                }
            }.onFailure { error ->
                val budget = error as? AgentBudgetExceededException
                val status = if (budget != null) RunStatus.TIMED_OUT else RunStatus.FAILED
                Log.w(TAG, "agent run " + run.id + " ended with " + status, error)
                dao.getById(run.id)?.let { row ->
                    val finishedAt = System.currentTimeMillis()
                    dao.upsert(
                        row.copy(
                            status = status.name,
                            error = error.message ?: error::class.simpleName,
                            finishedAt = finishedAt,
                            runtimeMs = finishedAt - (row.startedAt ?: finishedAt),
                        )
                    )
                }
            }
            jobs.remove(run.id)
        }
        jobs[run.id] = job
        active.cancel = job

        val handle = object : RunHandle {
            override suspend fun terminate(graceMillis: Long) {
                // 子代理没有进程要杀: 取消协程即可, 正在进行的模型请求随之中断。
                job.cancel()
            }

            override suspend fun await(timeoutMillis: Long): Boolean {
                val deadline = System.currentTimeMillis() + timeoutMillis
                while (System.currentTimeMillis() < deadline) {
                    if (job.isCompleted) return true
                    kotlinx.coroutines.delay(100)
                }
                return job.isCompleted
            }

            override val isAlive: Boolean get() = job.isActive
        }
        active.handle = handle
        return RunLaunchResult.Started(handle)
    }

    override fun isAlive(runId: String): Boolean = jobs[runId]?.isActive == true

    override suspend fun onFinish(active: ActiveRun, status: RunStatus, exitCode: Int?, error: String?) {
        // 状态已由上面的收尾分支写库（要区分 budget 与真实失败）, 这里不再重复写。
        jobs.remove(active.record.runId)
    }

    override suspend fun reconcileRegistry(registry: RunRegistry): Int {
        // 子代理没有需要收尾的进程: 协程随 App 进程一起消失, 收敛账目即可。
        jobs.clear()
        return registry.reconcile()
    }

    private fun buildInstructions(
        title: String,
        groups: Set<AgentToolGroup>,
        excludeJobTools: Boolean,
    ): String = buildString {
        appendLine("你是一个子代理, 由主助手派来独立完成一件具体的事。")
        appendLine()
        appendLine("任务: " + title)
        appendLine()
        appendLine("你可以使用这些能力: " + groups.joinToString(", ") { it.name.lowercase() } +
            if (excludeJobTools) "（不能启动后台任务）" else "")
        appendLine()
        appendLine("要求:")
        appendLine("1. 自己在工作区里把事做完, 不要反问用户 —— 你无法直接联系用户;")
        appendLine("2. 需要澄清时, 基于任务描述与你观察到的事实做最合理的判断, 并在结论里说明你的假设;")
        appendLine("3. 结束时用一段简洁的话说明: 做了什么、结果如何、有没有遗留问题;")
        appendLine("4. 不要描述你的思考过程, 直接给结论。")
    }

    companion object {
        /** 子代理默认轮次预算: 够做一件具体的事, 又不至于烧掉大量 token。 */
        const val DEFAULT_MAX_STEPS = 20
    }
}
