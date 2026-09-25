package me.rerere.rikkahub.data.workflow

import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceManager
import java.io.ByteArrayOutputStream

/**
 * 已保存的 workflow 脚本的落盘位置与读写。
 *
 * 脚本放在**工作区文件**里（/workspace/.rhe/workflows/<name>.js）而不是数据库:
 * 它是用户的可见资产 —— 可以用编辑器直接改、可以进 git、可以拷到别的设备。
 * 数据库里只放运行账目与可恢复性存档。
 *
 * 读写都经 Rootfs（跟其它工作区工具同一条路径）, 所以**依赖工作区 shell 已就绪**;
 * 调用方需要先确认 shellStatus, 否则这里会直接报错。
 */
class WorkflowScriptStore(
    private val workspaceRepository: WorkspaceRepository,
) {

    /** 保存（覆盖）并返回落盘路径。 */
    suspend fun save(workspaceId: String, name: String, source: String): String {
        val path = pathOf(name)
        // 路径直接拼进 shell 命令 —— 这是安全的, 因为 requireValidName 是**白名单**式的:
        // 只允许 [A-Za-z0-9._-] 且不以点开头, 里面不可能出现 shell 元字符（引号/分号/空格/$ 都没有）。
        // 刻意不引入 shellQuote: 那种转义函数一旦写错就是命令注入, 而"名字根本不含特殊字符"是更强的保证。
        val result = workspaceRepository.executeCommand(
            id = workspaceId,
            command = "mkdir -p -- " + DIR + " && cat > " + path,
            timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
            stdin = source.toByteArray(Charsets.UTF_8),
        )
        if (result.timedOut) error("saving workflow " + name + " timed out")
        if (result.exitCode != 0) {
            error("saving workflow " + name + " failed: " + result.stderr.ifBlank { result.stdout }.trim())
        }
        return path
    }

    /** 读回脚本; 不存在返回 null（调用方据此报"没有这个 workflow"）。 */
    suspend fun load(workspaceId: String, name: String): String? {
        val path = pathOf(name)
        val size = runCatching { workspaceRepository.rootfsFileSize(workspaceId, path) }.getOrNull()
            ?: return null
        if (size <= 0L) return null
        val out = ByteArrayOutputStream(size.toInt())
        runCatching { workspaceRepository.exportRootfsFile(workspaceId, path, out) }.getOrNull()
            ?: return null
        return out.toString(Charsets.UTF_8.name())
    }

    /** 列出已保存的 workflow 名（不含扩展名）。目录不存在时返回空表, 不报错。 */
    suspend fun list(workspaceId: String): List<String> {
        val result = runCatching {
            workspaceRepository.executeCommand(
                id = workspaceId,
                command = "ls -1 -- " + DIR + " 2>/dev/null || true",
                timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
            )
        }.getOrNull() ?: return emptyList()
        return result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.endsWith(EXTENSION) && it.length > EXTENSION.length }
            .map { it.removeSuffix(EXTENSION) }
            .filter { runCatching { requireValidName(it) }.isSuccess }
            .sorted()
            .toList()
    }

    suspend fun delete(workspaceId: String, name: String) {
        val path = pathOf(name)
        runCatching {
            workspaceRepository.executeCommand(
                id = workspaceId,
                command = "rm -f -- " + path,
                timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
            )
        }
    }

    /** 校验并返回合法的名字; 不合法直接抛（调用方会把它变成结构化错误）。 */
    fun requireValidName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "workflow name is required" }
        require(trimmed.length <= MAX_NAME_LENGTH) {
            "workflow name is too long (max " + MAX_NAME_LENGTH + ")"
        }
        require(VALID_NAME.matches(trimmed)) {
            "invalid workflow name: " + trimmed +
                " (allowed: letters, digits, dot, dash, underscore; must not start with a dot)"
        }
        return trimmed
    }

    fun pathOf(name: String): String = DIR + "/" + requireValidName(name) + EXTENSION

    companion object {
        /** 工作区文件区里的约定目录。放在这里是为了让用户能一眼找到自己的脚本。 */
        const val DIR = "/workspace/.rhe/workflows"
        const val EXTENSION = ".js"
        const val MAX_NAME_LENGTH = 64

        /** 白名单: 首字符为字母或数字, 其余允许点/横/下划线。点开头被排除, 免得出现隐藏文件与 .. 之类。 */
        private val VALID_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}
