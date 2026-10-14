package borg.trikeshed.parse.confix

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ConfixSerializationBoundaryTest {
    private val root = File(System.getProperty("user.dir") ?: fail("no user.dir"))
    private val formatDependency = Regex("""\bkotlinx-serialization-(?:json|cbor)(?:-[\w-]+)?\b""")
    private val formatNamespace = Regex("""\bkotlinx\s*\.\s*serialization\s*\.\s*(?:json|cbor)\b""")
    private val formatDeclaration = Regex(
        """\b(?:class|object|interface|typealias)\s+Confix(?:Format|Serialization|ItemEncoder|ItemDecoder|CborEncoder|CborDecoder)\b""",
    )

    private fun sources(): Sequence<File> {
        assertTrue(File(root, "build.gradle.kts").isFile, "project build must be present to scan")
        assertTrue(File(root, "src/commonMain/kotlin").isDirectory, "commonMain sources must be present to scan")
        // Scan source/build inputs, including embedded ingest and Gradle macros, not generated outputs.
        return root.walkTopDown()
            .onEnter { !it.name.startsWith(".") && it.name !in setOf("build", "node_modules") }
            .filter { it.isFile }
    }

    private fun File.sourceSet(): String? {
        val parts = relativeTo(root).invariantSeparatorsPath.split('/')
        val src = parts.indexOfLast { it == "src" }
        return if (src >= 0 && parts.getOrNull(src + 2) in setOf("kotlin", "java")) parts[src + 1] else null
    }

    private fun assertAbsent(files: Sequence<File>, pattern: Regex, rule: String) {
        val violations = files.flatMap { file ->
            val content = file.readText()
            pattern.findAll(content).map { match ->
                val line = content.take(match.range.first).count { it == '\n' } + 1
                "${file.relativeTo(root)}:$line: ${match.value}"
            }
        }.toList()
        assertTrue(violations.isEmpty(), "$rule:\n${violations.joinToString("\n")}")
    }

    @Test
    fun boundaryTestNoForbiddenSerializationDependencies() {
        assertAbsent(
            sources().filter {
                it.name.endsWith(".gradle.kts") || it.extension == "gradle" || it.name.endsWith(".versions.toml")
            },
            formatDependency,
            "Confix owns formats on every target; no kotlinx JSON/CBOR dependencies or catalog aliases",
        )
    }

    @Test
    fun boundaryTestNoForbiddenFormatFrontendsOnAnyTarget() {
        assertAbsent(
            sources().filter {
                it.extension in setOf("kt", "java") && it.sourceSet() != null
            },
            formatNamespace,
            "Sources and tests must use Confix formats, including on JVM",
        )
    }

    @Test
    fun boundaryTestNoSerializationInPlatformModules() {
        assertAbsent(
            sources().filter {
                it.extension == "kt" && it.sourceSet()?.let { name -> name != "commonMain" && !name.endsWith("Test") } == true
            },
            formatDeclaration,
            "Confix format implementations belong in commonMain; platform code may call them, not duplicate them",
        )
    }

    @Test
    fun boundaryPatternsRejectBothFormatsAndPlatformVariants() {
        for (format in listOf("json", "cbor")) {
            for (suffix in listOf("", "-jvm", "-js", "-wasm-js", "-macosarm64")) {
                assertTrue(formatDependency.containsMatchIn("org.jetbrains.kotlinx:kotlinx-serialization-$format$suffix:1.11.0"))
                assertTrue(formatDependency.containsMatchIn("module = \"org.jetbrains.kotlinx:kotlinx-serialization-$format$suffix\""))
            }
            assertTrue(formatNamespace.containsMatchIn("import kotlinx.serialization.$format.*"))
            assertTrue(formatNamespace.containsMatchIn("import kotlinx.serialization.$format.Format as Frontend"))
            assertTrue(formatNamespace.containsMatchIn("val format = kotlinx.serialization.$format.Format"))
            assertTrue(formatNamespace.containsMatchIn("val format = kotlinx.serialization.\n$format.Format"))
        }
        assertFalse(formatDependency.containsMatchIn("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0"))
        assertFalse(formatNamespace.containsMatchIn("import kotlinx.serialization.BinaryFormat"))
        assertTrue(formatDeclaration.containsMatchIn("object ConfixFormat : BinaryFormat"))
        assertTrue(formatDeclaration.containsMatchIn("class ConfixItemEncoder : AbstractEncoder()"))
        assertFalse(formatDeclaration.containsMatchIn("ConfixFormat.encodeToByteArray(serializer, value)"))
    }
}
