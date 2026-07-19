package borg.trikeshed.pointcut

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class AotClassfileTransformerTest {
    @Test
    fun transformDirectoryAcceptsAnEmptyDirectory() {
        val directory = Files.createTempDirectory("trikeshed-aot-empty")
        try {
            AotClassfileTransformer.transformDirectory(directory.toFile())
            assertTrue(directory.toFile().listFiles().isNullOrEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
