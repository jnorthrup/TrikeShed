package borg.trikeshed.hermes

import borg.trikeshed.lib.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

class HermesSourceInventoryTest {
    @Test
    @Suppress("UNCHECKED_CAST")
    fun excludedSourceDirectoriesDoNotRequireAccessToTheirDescendants() {
        val root = Files.createTempDirectory("hermes-source-inventory")
        val denied = s_[root.resolve("venv/denied"), root.resolve(".venv/denied"), root.resolve("nested/tests/denied")]
        try {
            assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"))
            Files.createDirectories(root.resolve("nested"))
            Files.writeString(root.resolve("nested/zeta.py"), "ZETA = 2")
            Files.writeString(root.resolve("alpha.py"), "ALPHA = 1")
            for (dir in denied.view) {
                Files.createDirectories(dir)
                Files.writeString(dir.resolve("unreachable.py"), "UNREACHABLE = True")
                Files.setPosixFilePermissions(dir, emptySet())
            }
            assumeTrue(denied.view.all { !Files.isReadable(it) }, "requires a user subject to directory permissions")

            HermesPythonPort().use { port ->
                val read = HermesPythonPort::class.java.getDeclaredMethod("readSources", Path::class.java, String::class.java)
                read.isAccessible = true
                val sources = read.invoke(port, root, "sleeve:") as Map<String, Pair<String, String>>

                assertEquals(listOf("alpha", "nested.zeta"), sources.keys.toList())
                assertEquals("sleeve:alpha.py" to "ALPHA = 1", sources["alpha"])
                assertEquals("sleeve:nested/zeta.py" to "ZETA = 2", sources["nested.zeta"])
            }
        } finally {
            for (dir in denied.view) {
                if (Files.exists(dir)) Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
            }
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
