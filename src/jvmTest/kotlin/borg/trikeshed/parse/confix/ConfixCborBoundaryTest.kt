package borg.trikeshed.parse.confix

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The CBOR encoder must not leak its scratch buffer type.
 *
 * This used to read `ConfixCborEncoder.kt` and assert the file contained
 * `internal class ByteArrayBuilder`. Commit c046bc88e ("merge three CBOR stacks
 * into ConfixFormat") deleted that file, and the builder with it — so the test
 * asserted the existence of its own subject and had been failing ever since,
 * guarding nothing. It is the same shape as the stale entries in `doc/todo.md`:
 * a check aimed at a world that moved.
 *
 * Rewritten to assert the invariant that OUTLIVED the consolidation rather than
 * the file that carried it: no portable source may publish a `ByteArrayBuilder`.
 * Today none declares one at all, so this passes by absence; the day someone
 * reintroduces it as public API, it fails — which is what the original was for.
 */
class ConfixCborBoundaryTest {

    @Test
    fun theCborScratchBufferIsNeverPublicApi() {
        val root = File(System.getProperty("user.dir") ?: fail("no user.dir"))
        val commonMain = File(root, "src/commonMain/kotlin")
        assertTrue(commonMain.isDirectory, "commonMain sources must be present to scan")

        val offenders = commonMain.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val declaring = f.readLines().withIndex().filter { (_, line) ->
                    Regex("""\bclass\s+ByteArrayBuilder\b""").containsMatchIn(line)
                }
                if (declaring.isEmpty()) null
                else {
                    val leaked = declaring.filterNot { (_, l) ->
                        l.contains("internal ") || l.contains("private ")
                    }
                    if (leaked.isEmpty()) null
                    else "${f.relativeTo(root)}: " + leaked.joinToString("; ") { (i, l) -> "line ${i + 1}: ${l.trim()}" }
                }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "ByteArrayBuilder must stay internal or private — it is the encoder's scratch buffer, " +
                "not API:\n  " + offenders.joinToString("\n  "),
        )
    }
}
