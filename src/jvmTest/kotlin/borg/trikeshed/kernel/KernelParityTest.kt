package borg.trikeshed.kernel

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Kernel parity with CCEKCMMKPlatform (doc/kernel/README.md).
 *
 * The algebraic kernel — Join, Series, Cursor, CharStr, Confix — exists in two
 * repositories under the same paths. `doc/kernel/parity.tsv` is the decision
 * per file; this test holds the working tree to it, the way
 * RouteManifestParityTest holds routes to RouteManifest:
 *
 *  - `identical`: the file must still hash to the platform's bytes. Editing it
 *    here is a decision — re-declare the row `ahead` (or bring the platform
 *    along) by running `scripts/kernel-parity.sh`.
 *  - `ahead`: the file must still differ from the platform's bytes; a row that
 *    has become identical again is a stale declaration.
 *  - `moved:<path>`: the file must exist at its new path.
 *  - `absent`: never acceptable — a kernel file the platform has and we lost.
 */
class KernelParityTest {

    private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "doc/kernel/parity.tsv").exists() }

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private data class Row(val path: String, val status: String, val platformSha: String, val resolved: String)

    private fun rows(): List<Row> = File(repo, "doc/kernel/parity.tsv").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("path\t") }
        .map { l -> val c = l.split('\t'); Row(c[0], c[1], c[2], c.getOrElse(4) { "" }) }

    @Test
    fun theManifestIsNonTrivialAndWellFormed() {
        val r = rows()
        assertTrue(r.size >= 30, "the platform holds dozens of kernel files; got ${r.size}")
        r.forEach { assertTrue(it.platformSha.length == 64, "${it.path}: platform sha") }
        assertTrue(r.any { it.status == "identical" }, "some shared files are byte-identical")
    }

    @Test
    fun everyRowHoldsAgainstTheWorkingTree() {
        val problems = ArrayList<String>()
        for (row in rows()) {
            val f = File(repo, row.resolved.ifEmpty { row.path })
            when {
                row.status == "absent" -> problems += "${row.path}: ABSENT here — the platform has a kernel file we lost"
                !f.exists() -> problems += "${row.path}: declared ${row.status} but ${row.resolved} does not exist"
                row.status == "identical" && sha256(f) != row.platformSha ->
                    problems += "${row.path}: declared identical to the platform but was edited here — decide: re-declare ahead (scripts/kernel-parity.sh) or carry the change to the platform"
                row.status == "ahead" && sha256(f) == row.platformSha ->
                    problems += "${row.path}: declared ahead but is byte-identical to the platform — stale row, regenerate"
                row.status.startsWith("moved:") && row.status.removePrefix("moved:") != row.resolved ->
                    problems += "${row.path}: moved row disagrees with its resolved path"
            }
        }
        if (problems.isNotEmpty()) fail("kernel parity broken:\n  " + problems.joinToString("\n  "))
    }
}
