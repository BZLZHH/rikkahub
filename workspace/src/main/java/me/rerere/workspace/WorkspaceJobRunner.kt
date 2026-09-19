package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 一个已经启动的后台任务进程句柄。
 *
 * 与 [WorkspaceCommandResult] 不同, 它不等待、不收集输出: 输出由调用方提供的流自行落盘,
 * 生命周期由调用方 (Android 侧的任务管理器) 掌控。
 */
class RunningWorkspaceJob internal constructor(
    private val process: Process,
) {
    /**
     * 子进程 pid。
     * Android 的 java.lang.Process 没有 pid()/toHandle(), 因此这里用反射尽力而为,
     * 拿不到就返回 -1（只影响展示, 不影响功能）。
     */
    val pid: Long = runCatching {
        (process.javaClass.getMethod("pid").invoke(process) as? Long) ?: -1L
    }.getOrDefault(-1L)

    fun isAlive(): Boolean = process.isAlive

    fun waitFor(): Int = process.waitFor()

    fun exitCode(): Int? = runCatching { process.exitValue() }.getOrNull()

    /**
     * 停止任务: 先 TERM, 宽限期后仍未退出则 KILL。
     *
     * 只杀直接子进程 (PRoot) 即可: PRoot 以 --kill-on-exit 启动, 它自己退出时会清理整棵子树。
     */
    fun terminate(graceMillis: Long = TERMINATE_GRACE_MS) {
        runCatching { process.destroy() }
        val deadline = System.currentTimeMillis() + graceMillis
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) return
            Thread.sleep(50)
        }
        runCatching { process.destroyForcibly() }
    }

    companion object {
        const val TERMINATE_GRACE_MS = 1_500L
    }
}

/**
 * 后台任务进程启动器。
 *
 * 与 [ProotShellRunner] 的关键差异:
 * - 不阻塞等待, 不把输出收集进内存;
 * - 不做超时强杀 (超时策略由 Android 侧的任务管理器掌握, 因为它还要落库/发通知);
 * - 立即关闭 stdin, 避免按 isatty 判断的 CLI 永久阻塞 (与同步执行保持一致)。
 */
class WorkspaceJobRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) {
    fun start(
        context: WorkspaceShellContext,
        stdout: OutputStream,
        stderr: OutputStream,
        extraEnv: Map<String, String> = emptyMap(),
    ): RunningWorkspaceJob {
        require(ProotCommand.hasUsableRootfs(context.linuxDir)) {
            "Rootfs is not installed: ${context.linuxDir.absolutePath}"
        }
        val proot = File(nativeLibraryDir, ProotCommand.PROOT_EXEC)
        val loader = File(nativeLibraryDir, ProotCommand.PROOT_LOADER)
        require(proot.isFile) { "proot executable not found: ${proot.absolutePath}" }
        require(loader.isFile) { "proot loader not found: ${loader.absolutePath}" }

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)

        val process = ProcessBuilder(
            ProotCommand.build(context, proot, extraEnv = extraEnv)
        )
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply { ProotCommand.applyEnvironment(environment(), context, loader, extraEnv) }
            .start()

        // 没有 stdin 输入时立即关闭管道, 让子进程读到 EOF
        runCatching { process.outputStream.close() }

        pump(process.inputStream, stdout, "rikkahub-job-stdout")
        pump(process.errorStream, stderr, "rikkahub-job-stderr")

        return RunningWorkspaceJob(process)
    }

    private fun pump(input: InputStream, output: OutputStream, name: String) {
        Thread({
            try {
                input.use { source ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        output.flush()
                    }
                }
            } catch (_: Throwable) {
                // 进程被杀/流被关闭时保留已写内容即可, 不能让异常逃逸
            }
        }, name).apply {
            isDaemon = true
            start()
        }
    }
}
