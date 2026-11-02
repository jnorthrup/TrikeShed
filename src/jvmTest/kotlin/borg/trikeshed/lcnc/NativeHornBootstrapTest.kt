package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.job.fileIoContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.*

class NativeHornBootstrapTest {
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "trikeshed.native.mlnix", matches = ".+")
    @Test fun pinnedOperatorConfigurationLoadsThroughFileCapability(): Unit = runBlocking(JvmFileOperations()) {
        val files = kotlin.coroutines.coroutineContext[FileOperations]!!
        val directory = withContext(fileIoContext) { files.createTempDir("native-profile-test-") }
        try {
            val path = files.resolvePath(directory, "profile.json")
            withContext(fileIoContext) {
                files.write(path, jsonOf(_m[
                    "binary" j System.getProperty("trikeshed.native.mlnix"),
                    "binarySha256" j System.getProperty("trikeshed.native.mlnix.sha256"),
                    "adapter" j System.getProperty("trikeshed.native.adapter"),
                    "datalogDirectory" j System.getProperty("trikeshed.native.datalog"),
                ]).encodeToByteArray())
            }
            assertNotNull(OroborosLcncWiring.nativeHorn(files, path))
        } finally {
            withContext(fileIoContext) { files.deleteRecursively(directory) }
        }
    }

    @Test fun operatorConfigurationFailsClosedBeforeOpeningNativeCode(): Unit = runBlocking {
        val memory = InMemoryFileOperations()
        var read = false
        val files = object : FileOperations by memory {
            override fun readAllBytes(filename: String): ByteArray {
                read = true
                return memory.readAllBytes(filename)
            }
        }
        assertNull(OroborosLcncWiring.nativeHorn(files, null))
        assertFalse(read)
        assertFailsWith<IllegalArgumentException> { OroborosLcncWiring.nativeHorn(files, "relative.json") }
        memory.write("/profile.json", ByteArray(16 * 1024 + 1))
        assertFailsWith<IllegalArgumentException> { OroborosLcncWiring.nativeHorn(files, "/profile.json") }
        assertFalse(read, "Oversized operator configuration must fail before reading its content")
        memory.write("/native", "unapproved executable".encodeToByteArray())
        memory.write("/profile.json", jsonOf(_m[
            "binary" j "/native", "binarySha256" j "0".repeat(64),
            "adapter" j "/adapter.nix", "datalogDirectory" j "/source",
        ]).encodeToByteArray())
        val mismatch = assertFailsWith<IllegalArgumentException> { OroborosLcncWiring.nativeHorn(files, "/profile.json") }
        assertTrue(mismatch.message.orEmpty().contains("executable pin differs"))
    }
}
