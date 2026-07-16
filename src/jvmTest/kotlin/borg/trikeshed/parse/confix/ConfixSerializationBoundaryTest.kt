package borg.trikeshed.parse.confix

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.fail

class ConfixSerializationBoundaryTest {
    @Test
    fun nonCoreSerializersCannotEnterPortableConfixBoundary() {
        val forbidden = listOf(
            "kotlinx.serialization.json",
            "kotlinx.serialization.cbor",
            "kotlinx.serialization.protobuf",
            "kotlinx.serialization.properties",
        )
        val offenders = mutableListOf<String>()

        listOf(
            Path.of("src/commonMain/kotlin"),
            Path.of("src/commonTest/kotlin"),
        ).filter(Files::exists).forEach { root ->
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { path ->
                        val text = path.readText()
                        forbidden.filter(text::contains).forEach { serializer ->
                            offenders += "$path imports or references $serializer"
                        }
                    }
            }
        }

        val buildFile = Path.of("build.gradle.kts").readText()
        val commonMainBlock = buildFile
            .substringAfter("val commonMain = getByName(\"commonMain\")", missingDelimiterValue = "")
            .substringBefore("val commonTest = getByName(\"commonTest\")", missingDelimiterValue = "")
        forbidden.map { it.substringAfterLast('.') }.forEach { format ->
            val artifact = "kotlinx-serialization-$format"
            if (artifact in commonMainBlock) {
                offenders += "build.gradle.kts commonMain depends on $artifact"
            }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "Confix is the sole portable serializer; only kotlinx-serialization-core " +
                    "may enter commonMain/commonTest:\n${offenders.joinToString("\n")}",
            )
        }
    }
}
