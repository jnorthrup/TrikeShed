package borg.trikeshed.graal.subvm

import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.vm.Teleported
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class PythonFileLeasesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun competingOwnerIsRefusedUntilTheFilesystemLeaseCloses() {
        val files = JvmFileOperations()
        val root = directory.toString()
        val published = directory.resolve("registry.json")
        val write: (String, ByteArray) -> Unit = { _, bytes -> files.writeAtomically(published.toString(), bytes) }
        val first = PythonFileLeases(files, root, write)
        PythonFileLeases(files, root, write).use { second ->
            val acquire = listOf(Teleported.Str("acquire"), Teleported.Str("/hermes/runtime/active_sessions.lock"))
            first.call(acquire)
            assertFails { second.call(acquire) }
            first.call(listOf(Teleported.Str("publish"), Teleported.Str("/hermes/runtime/active_sessions.json"), Teleported.Str("{\"entries\":[]}")))
            assertEquals("{\"entries\":[]}", files.readString(published.toString()))
            first.close()
            val lease = assertIs<Teleported.Str>(second.call(acquire))
            assertEquals(Teleported.Bool(true), second.call(listOf(Teleported.Str("release"), lease)))
        }
        first.close()
    }
}
