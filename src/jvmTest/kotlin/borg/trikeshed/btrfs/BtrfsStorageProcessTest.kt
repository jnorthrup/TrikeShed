package borg.trikeshed.btrfs

import borg.trikeshed.graal.subvm.GraalBtrfsSupervisor
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.vm.Teleported
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(90)
class BtrfsStorageProcessTest {
    @Test
    fun acknowledgedGuestWritesAndSnapshotsSurviveAbruptProcessExit() {
        val root = Files.createTempDirectory("trikeshed-storage-process-")
        try {
            child("write", root, 73)
            child("read", root, 0)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun child(mode: String, root: Path, expectedExit: Int) {
        val log = root.resolve("$mode.log")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx512m", "-Dpolyglot.engine.WarnInterpreterOnly=false",
            "-cp", System.getProperty("trikeshed.storage.classpath", System.getProperty("java.class.path")),
            BtrfsStorageProcess::class.java.name, mode, root.toString(),
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(35, TimeUnit.SECONDS), "Storage child exceeded 35s: $mode")
            val output = Files.readString(log)
            println(output)
            assertEquals(expectedExit, process.exitValue(), output)
            assertTrue(output.contains("STORAGE_${mode.uppercase()}_OK"), output)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                check(process.waitFor(5, TimeUnit.SECONDS)) { "Storage child did not terminate" }
            }
        }
    }
}

object BtrfsStorageProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2 && args[0] in setOf("write", "read"))
        val root = args[1]
        val files = JvmFileOperations()
        val store = BtrfsWorldStore.ofFiles(files, "$root/worlds", cas = FileCasStore(files, "$root/cas"))
        val vm = GraalBtrfsSupervisor("release.guest", VmFacet.GRAAL_PYTHON, world = store)
        if (args[0] == "write") {
            vm.eval("with open('/workspace/state.txt', 'w') as f:\n    f.write('before')", "write.py")
            check(vm.snapshot("release.snapshot"))
            vm.eval("with open('/workspace/state.txt', 'w') as f:\n    f.write('after')", "update.py")
            check(vm.eval("open('/workspace/state.txt').read()", "read.py") == Teleported.Str("after"))
            println("STORAGE_WRITE_OK pid=${ProcessHandle.current().pid()} root=$root")
            System.out.flush()
            // Skip guest close and JVM shutdown hooks: the next process must use disk only.
            Runtime.getRuntime().halt(73)
        }
        vm.use {
            check(it.eval("open('/workspace/state.txt').read()", "reopen.py") == Teleported.Str("after"))
        }
        val mount = store.mount("release.guest")
        check(mount.fetchFile("release.snapshot", "workspace/state.txt")?.decodeToString() == "before")
        check(!mount.writeFile("release.snapshot", "workspace/state.txt", "changed".encodeToByteArray()))
        val manifest = requireNotNull(mount.manifest("release.guest"))
        check(manifest.encode().contentEquals(requireNotNull(store.mount("release.guest").manifest("release.guest")).encode()))
        println("STORAGE_READ_OK pid=${ProcessHandle.current().pid()} root=$root")
    }
}
