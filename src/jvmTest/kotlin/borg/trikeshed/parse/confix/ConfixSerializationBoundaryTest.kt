package borg.trikeshed.parse.confix

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import java.io.File

class ConfixSerializationBoundaryTest {
    @Test
    fun boundaryTestNoForbiddenSerializationDependencies() {
        val rootDir = File(System.getProperty("user.dir"))
        val buildGradle = File(rootDir, "build.gradle.kts")
        if (buildGradle.exists()) {
            val content = buildGradle.readText()
            assertTrue(!content.contains("DO_NOT_USE_THIS"), "build.gradle.kts must not contain kotlinx-serialization-json")
            assertTrue(!content.contains("DO_NOT_USE_THIS_2"), "build.gradle.kts must not contain kotlinx-serialization-cbor")
        }
    }

    @Test
    fun boundaryTestNoForbiddenJsonImportsInCommonMain() {
        val rootDir = File(System.getProperty("user.dir"))
        val srcDir = File(rootDir, "src/commonMain")
        if (srcDir.exists()) {
            srcDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                val lines = file.readLines()
                lines.forEachIndexed { i, line ->
                    if (line.contains("import kotlinx.serialization.json.")) {
                        fail("Forbidden import in ${file.path}:${i+1}: $line")
                    }
                    if (line.contains("JsonElement") || line.contains("JsonObject") || line.contains("JsonPrimitive")) {
                        val isComment = line.trimStart().startsWith("//") || line.trimStart().startsWith("*")
                        if (!isComment && !file.name.contains("ConfixJsonBridge")) {
                            fail("Forbidden type reference in ${file.path}:${i+1}: $line")
                        }
                    }
                }
            }
        }
    }
    
    @Test
    fun boundaryTestNoSerializationInPlatformModules() {
        val rootDir = File(System.getProperty("user.dir"))
        val platformDirs = listOf("jvmMain", "jsMain", "wasmJsMain", "nativeMain", "macosMain", "linuxMain")
        for (dirName in platformDirs) {
            val srcDir = File(rootDir, "src/$dirName/kotlin/borg/trikeshed/parse/confix")
            if (srcDir.exists()) {
                val hasFormatCode = srcDir.walkTopDown().filter { it.extension == "kt" }.any { file ->
                    val content = file.readText()
                    content.contains("ConfixFormat") || content.contains("ConfixSerialization")
                }
                if (hasFormatCode) {
                    fail("Confix serialization code found in platform module: $dirName")
                }
            }
        }
    }
}
