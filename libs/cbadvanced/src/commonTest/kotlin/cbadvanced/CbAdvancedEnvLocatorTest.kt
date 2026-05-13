package cbadvanced

import borg.trikeshed.userspace.nio.file.Files
import borg.trikeshed.userspace.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CbAdvancedEnvLocatorTest {
    @Test
    fun resolveDotenvPath_prefersLocalDotenv() {
        val root = Files.createTempDirectory("cbadvanced-local")
        val local = root.resolve(".env")
        Files.writeString(local, "LOCAL=1\n")

        val dreamer = root.resolveSibling("dreamer-kmm")
        Files.createDirectories(dreamer)
        Files.writeString(dreamer.resolve(".env"), "DREAMER=1\n")

        assertEquals(local, resolveDotenvPath(workingDir = root))
    }

    @Test
    fun resolveDotenvPath_fallsBackToDreamerSibling() {
        val parent = Files.createTempDirectory("cbadvanced-parent")
        val workingDir = parent.resolve("cbadvanced")
        Files.createDirectories(workingDir)

        val expected = parent.resolve("dreamer-kmm").resolve(".env")
        Files.createDirectories(expected.parent)
        Files.writeString(expected, "COINBASE_API_KEY=demo\n")

        assertEquals(expected, resolveDotenvPath(workingDir = workingDir))
    }

    @Test
    fun resolveDotenvPath_honorsExplicitOverride() {
        val workingDir = Files.createTempDirectory("cbadvanced-override")
        val explicit = workingDir.resolve("custom.env")
        Files.writeString(explicit, "COINBASE_API_KEY=demo\n")

        assertEquals(explicit, resolveDotenvPath(explicitPath = explicit.toString(), workingDir = workingDir))
    }
}