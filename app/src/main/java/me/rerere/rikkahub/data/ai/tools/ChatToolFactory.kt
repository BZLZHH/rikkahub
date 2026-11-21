package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.deepSeekToolsEnabled
import me.rerere.ai.provider.supportsDeepSeekFlash
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.db.dao.WorkspaceJobDAO
import me.rerere.rikkahub.data.job.JobScheduleEngine
import me.rerere.rikkahub.data.job.WorkspaceJobManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.service.BackgroundShellManager
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")

internal fun shouldUseExternalWebSearch(
    assistant: Assistant,
    model: Model,
    provider: ProviderSetting? = null,
): Boolean {
    val nativeDeepSeek = provider?.supportsDeepSeekFlash(model) == true && model.deepSeekToolsEnabled
    val nativeSearch = if (nativeDeepSeek) model.deepSeek.webTools else BuiltInTools.Search in model.tools
    return assistant.enableWebSearch && !nativeSearch
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val jobManager: WorkspaceJobManager,
    private val scheduleEngine: JobScheduleEngine,
    private val jobDao: WorkspaceJobDAO,
    /** 子代理的账本与转录; 与 job 分表, 但共用同一套编排层 */
    private val agentRunDao: me.rerere.rikkahub.data.db.dao.AgentRunDAO,
    private val agentTranscripts: me.rerere.rikkahub.data.agent.AgentTranscriptStore,
    private val runOrchestrator: me.rerere.rikkahub.data.run.RunOrchestrator,
    /** workflow: 运行服务（脚本编排）/ 脚本落盘 / 运行账目 */
    private val workflowRunner: me.rerere.rikkahub.data.workflow.WorkflowRunner,
    private val workflowScriptStore: me.rerere.rikkahub.data.workflow.WorkflowScriptStore,
    private val workflowRunDao: me.rerere.rikkahub.data.db.dao.WorkflowRunDAO,
    private val backgroundShellManager: BackgroundShellManager,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        conversationId: Uuid? = null,
        workspaceCwd: String? = null,
        latestAttachments: List<UIMessagePart> = emptyList(),
    ): List<Tool> {
        val workspaceTools =
            createWorkspaceToolsIfReady(
                workspaceId = assistant.workspaceId?.toString(),
                cwd = workspaceCwd,
                conversationId = conversationId,
                assistantId = assistant.id.toString(),
                modelId = model.id.toString(),
                executionSnapshot = me.rerere.rikkahub.data.agent.AgentExecutionSnapshot(assistant, model, workspaceCwd),
            )
        val tools = buildList {
            if (assistant.enableMemory) {
                addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
            }
            if (shouldUseExternalWebSearch(assistant, model, model.findProvider(settings.providers))) {
                addAll(createSearchTools(settings))
            }
            addAll(localTools.getTools(assistant.localTools))
            if (LocalToolOption.DeepSeekOcr in assistant.localTools) {
                val apiKey = settings.resolveDeepSeekOcrApiKey()
                if (apiKey != null) buildDeepSeekOcrTool(context, apiKey, latestAttachments)?.let { add(it) }
            }
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, assistant.id))
            }
            addAll(workspaceTools)
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                    )
                )
            }

            val mcpTools = mcpManager.getAllAvailableTools(assistant)
            val invalidNames = mcpTools
                .map { it.second }
                .distinct()
                .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
            if (invalidNames.isNotEmpty()) {
                throw InvalidMcpServerNamesException(invalidNames)
            }
            mcpTools.forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                    )
                )
            }
        }
        // 过长的输出存成文件让模型用 shell 按需读取；没有 shell 时模型读不到文件，只能原样返回。
        // 文件放在本机, 只有本地 Rootfs 挂载了 /tool_outputs, 远程工作区同样读不到
        val canReadToolOutputs = workspaceTools.isNotEmpty() &&
            assistant.workspaceId?.let { workspaceRepository.getById(it.toString()) }?.capabilities()?.rootfs == true
        return if (canReadToolOutputs) tools.map(::withOutputTruncation) else tools
    }

    private fun withOutputTruncation(tool: Tool): Tool {
        if (tool.name in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return tool
        val execute = tool.execute
        return tool.copy(execute = { truncateOutput(execute(it)) })
    }

    private fun truncateOutput(output: List<UIMessagePart>): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val totalChars = textParts.sumOf { it.text.length }
        if (totalChars <= MAX_TOOL_OUTPUT_CHARS) return output

        val fileName = "${Uuid.random()}.txt"
        Log.i(TAG, "truncateOutput: saving $totalChars chars to $fileName")
        val fullText = textParts.joinToString("\n") { it.text }
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(fullText.take(TOOL_OUTPUT_PREVIEW_CHARS))
                }
            )
        ) + output.filter { it !is UIMessagePart.Text }
    }

    private suspend fun createWorkspaceToolsIfReady(
        workspaceId: String?,
        cwd: String?,
        conversationId: Uuid?,
        assistantId: String?,
        modelId: String?,
        executionSnapshot: me.rerere.rikkahub.data.agent.AgentExecutionSnapshot,
    ): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(
            workspaceId = workspaceId,
            workspaceRepository = workspaceRepository,
            cwd = cwd,
            conversationId = conversationId,
            backgroundShellManager = backgroundShellManager,
            jobContext = if (workspace.capabilities().rootfs) JobToolContext(
                workspaceId = workspaceId,
                conversationId = conversationId?.toString(),
                assistantId = assistantId,
                manager = jobManager,
                scheduleEngine = scheduleEngine,
                dao = jobDao,
            ) else null,
            // 子代理: 与 shell 后台任务平级的另一种执行体, 共用同一套编排层
            subagentContext = SubagentToolContext(
                workspaceId = workspaceId,
                conversationId = conversationId?.toString(),
                assistantId = assistantId,
                dao = agentRunDao,
                transcripts = agentTranscripts,
                orchestrator = runOrchestrator,
                executionSnapshot = executionSnapshot,
            ),
            // workflow: 与 job_* / subagent_* 平级的第三种"起执行体"的能力,
            // 区别是它起的是**一段代码**, 由脚本自己决定起多少个子代理。
            workflowContext = WorkflowToolContext(
                workspaceId = workspaceId,
                defaultModelId = modelId,
                conversationId = conversationId?.toString(),
                assistantId = assistantId,
                dao = workflowRunDao,
                runner = workflowRunner,
                scripts = workflowScriptStore,
                executionSnapshot = executionSnapshot,
            ),
        )
    }
}
