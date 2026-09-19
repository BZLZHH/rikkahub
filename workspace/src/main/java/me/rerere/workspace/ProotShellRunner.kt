package me.rerere.workspace

import java.io.File

data class WorkspaceBindMount(
    val source: File,
    val target: String,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

/**
 * 同步执行器: 阻塞直到命令结束, 输出收集进内存（上限见 [MAX_OUTPUT_CHARS]）。
 * 命令行构造见 [ProotCommand]（与后台任务共用）。
 */
class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!ProotCommand.hasUsableRootfs(context.linuxDir)) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }

        val proot = File(nativeLibraryDir, ProotCommand.PROOT_EXEC)
        val loader = File(nativeLibraryDir, ProotCommand.PROOT_LOADER)
        if (!proot.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${proot.absolutePath}",
            )
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        val process = ProcessBuilder(ProotCommand.build(context, proot))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply { ProotCommand.applyEnvironment(environment(), context, loader) }
            .start()

        return process.readResult(context.timeoutMillis, context.stdin)
    }
}
