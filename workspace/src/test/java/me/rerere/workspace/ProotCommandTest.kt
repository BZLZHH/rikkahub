package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ProotCommandTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun alpineAbsoluteShellSymlinkIsUsableForJobsAndCommands() {
        val linux = tempFolder.newFolder("linux")
        File(linux, "bin").mkdirs()
        Files.createSymbolicLink(File(linux, "bin/sh").toPath(), File("/bin/busybox").toPath())

        assertTrue(ProotCommand.hasUsableRootfs(linux))
        assertEquals("/bin/sh", WorkspaceManager.rootfsShell(linux))
    }

    @Test
    fun trackedCommandsKeepCommandAndCwdAsPositionArguments() {
        val files = tempFolder.newFolder("files")
        val linux = tempFolder.newFolder("linux")
        val tmp = tempFolder.newFolder("tmp")
        val mount = tempFolder.newFolder("skills")
        val command = "printf '%s' 'space and quotes'"
        val script = "custom positional script"
        val context = WorkspaceShellContext(
            root = "test",
            command = command,
            cwd = "nested folder",
            filesDir = files,
            linuxDir = linux,
            tempDir = tmp,
            workingDir = files,
            timeoutMillis = 1_000,
            bindMounts = listOf(WorkspaceBindMount(mount, "/skills")),
        )
        val args = ProotCommand.build(
            context = context,
            proot = File("/native/libproot_exec.so"),
            extraEnv = mapOf("JOB_VALUE" to "two words"),
            script = script,
        ) + "/tmp/test.pid"

        assertEquals(listOf(script, "rikkahub", "/workspace/nested folder", command, "/tmp/test.pid"), args.takeLast(5))
        assertTrue(mount.absolutePath + ":/skills" in args)
        assertTrue(args.indexOf("JOB_VALUE=two words") > args.indexOf("-i"))
        assertTrue(args.indexOf("JOB_VALUE=two words") < args.indexOf("/bin/sh"))
    }

    @Test
    fun ptyJobsUseConfiguredMountsCwdAndGuestEnvironment() {
        val native = tempFolder.newFolder("native")
        val upload = tempFolder.newFolder("upload")
        val shared = tempFolder.newFolder("shared directory")
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            shellRunner = ProotShellRunner(native),
            bindMounts = listOf(WorkspaceBindMount(upload, "/upload")),
        )
        manager.ensureWorkspace("test")
        val command = "printf '%s' 'space and quotes'"
        val launch = manager.terminalLaunch(
            root = "test",
            shellCompatibilityMode = true,
            command = command,
            cwd = "nested folder",
            extraEnv = mapOf("JOB_VALUE" to "two words"),
            extraMounts = listOf(WorkspaceBindMount(shared, "/storage/emulated/0/Documents")),
        )!!

        assertEquals(listOf("rikkahub", "/workspace/nested folder", command), launch.args.takeLast(3))
        assertTrue(upload.absolutePath + ":/upload" in launch.args)
        assertTrue(shared.absolutePath + ":/storage/emulated/0/Documents" in launch.args)
        assertTrue(launch.args.indexOf("JOB_VALUE=two words") > launch.args.indexOf("-i"))
        assertTrue(launch.args.indexOf("JOB_VALUE=two words") < launch.args.indexOf("/bin/sh"))
        assertTrue("PROOT_NO_SECCOMP=1" in launch.env)
    }
}
