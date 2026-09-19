package me.rerere.workspace

import java.io.File

/**
 * PRoot 命令行构造。
 *
 * 同步执行（[ProotShellRunner]）与后台任务（[WorkspaceJobRunner]）共用同一套参数与环境,
 * 避免两处漂移导致"同样的命令在前后台行为不一致"。
 */
internal object ProotCommand {
    const val PROOT_EXEC = "libproot_exec.so"
    const val PROOT_LOADER = "libproot_loader.so"

    private val BASE_ENV = listOf(
        "HOME=/root",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "LC_ALL=C.UTF-8",
        // 非交互执行约定, 抑制各类 CLI 的交互行为 (确认提示/分页器/颜色转义)
        "CI=true",
        "NO_COLOR=1",
        "PAGER=cat",
    )

    fun hasUsableRootfs(dir: File): Boolean = dir.isDirectory && File(dir, "bin/sh").isFile

    fun build(
        context: WorkspaceShellContext,
        proot: File,
        command: String = context.command,
        cwd: String = context.cwd,
        extraEnv: Map<String, String> = emptyMap(),
    ): List<String> {
        val args = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            context.linuxDir.absolutePath,
            "-w",
            context.prootCwd(cwd),
            "-b",
            "${context.filesDir.absolutePath}:${WorkspaceManager.ROOTFS_WORKSPACE_DIR}",
        )

        context.bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                args += "-b"
                args += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                args += "-b"
                args += path
            }
        }

        args += "/usr/bin/env"
        args += "-i"
        args += BASE_ENV
        extraEnv.forEach { (key, value) -> args += "$key=$value" }
        args += listOf(
            "/bin/bash",
            "-l",
            "-c",
            // 命令通过位置参数传入, 避免任何转义; eval "\$2" 对命令文本只求值一次, 等价于 bash -c "$cmd"
            "cd -- \"\$1\" && eval \"\$2\"",
            "rikkahub",
            context.prootCwd(cwd),
            command,
        )
        return args
    }

    /** 把 PRoot 需要的环境变量写入 [target]（调用方传入 ProcessBuilder 的 environment()）。 */
    fun applyEnvironment(
        target: MutableMap<String, String>,
        context: WorkspaceShellContext,
        loader: File,
        extraEnv: Map<String, String> = emptyMap(),
    ) {
        if (context.shellCompatibilityMode) {
            target["PROOT_NO_SECCOMP"] = "1"
        } else {
            target.remove("PROOT_NO_SECCOMP")
        }
        target["PROOT_LOADER"] = loader.absolutePath
        target["PROOT_TMP_DIR"] = context.tempDir.absolutePath
        target["TMPDIR"] = context.tempDir.absolutePath
        target.putAll(extraEnv)
    }

    private fun WorkspaceShellContext.prootCwd(cwd: String): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WorkspaceManager.ROOTFS_WORKSPACE_DIR
        } else {
            "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/$normalized"
        }
    }
}
