package cbadvanced

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class CbAdvancedEnvLocatorTest {
    @Test
    fun resolveDotenvPath_prefersLocalDotenv() {
        val root = createTempDirectory("cbadvanced-local")
        val local = root.resolve(".env")
        local.writeText("LOCAL=1\n")

        val dreamer = root.resolveSibling("dreamer-kmm")
        dreamer.createDirectories()
        dreamer.resolve(".env").writeText("DREAMER=1\n")

        assertEquals(local.toAbsolutePath().normalize(), resolveDotenvPath(workingDir = root).toAbsolutePath().normalize())
    }

    @Test
    fun resolveDotenvPath_fallsBackToDreamerSibling() {
        val parent = createTempDirectory("cbadvanced-parent")
        val workingDir = parent.resolve("cbadvanced")
        workingDir.createDirectories()

        val expected = parent.resolve("dreamer-kmm").resolve(".env")
        expected.parent.createDirectories()
        expected.writeText("COINBASE_API_KEY=demo\n")

        assertEquals(expected.toAbsolutePath().normalize(), resolveDotenvPath(workingDir = workingDir).toAbsolutePath().normalize())
    }

    @Test
    fun resolveDotenvPath_honorsExplicitOverride() {
        val workingDir = createTempDirectory("cbadvanced-override")
        val explicit = workingDir.resolve("custom.env")
        explicit.writeText("COINBASE_API_KEY=demo\n")

        assertEquals(
            explicit.toAbsolutePath().normalize(),
            resolveDotenvPath(explicitPath = explicit.toString(), workingDir = workingDir).toAbsolutePath().normalize(),
        )
    }
}
