package borg.trikeshed.lcnc

import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `vm.modules` — the audit surface for the classpaths this daemon can execute guest code from.
 *
 * The sub-VM's whole safety argument had been living in a KDoc: a comment said a mounted module is
 * verified and narrowed, and an operator had no way to ask the machine. This lego is that question,
 * asked declaratively, on the same surface as everything else and with no bespoke UI.
 *
 * The security property under test is NOT "the body refuses to mutate". It is that the lego has no
 * port through which mutation could be expressed — `LcncContracts` declares no inputs and two
 * outputs, so nothing upstream can steer it and the worst a wrong param yields is a row saying a
 * module is not installed. Capability is the declaration.
 */
class VmModulesLegoTest {

    /** JsonSupport reifies arrays as Array<Any?>, not List — and numbers as Double ("28.0"). */
    private fun rows(obj: Map<*, *>): List<Map<*, *>> = when (val m = obj["modules"]) {
        is List<*> -> m
        is Array<*> -> m.toList()
        else -> error("modules was neither list nor array: ${m?.let { it::class }} = $m")
    }.map { it as Map<*, *> }

    private fun num(v: Any?): Long = v.toString().toDouble().toLong()

    private fun run(params: Map<String, String>): Map<*, *> = kotlinx.coroutines.runBlocking {
        val out = SubVmLegos.modules().run(LcncNode("m1", SubVmLegos.MODULES, params = params), emptyMap())
        JsonSupport.parse(out["modules"].toString()) as? Map<*, *>
            ?: error("vm.modules did not emit a JSON object: ${out["modules"]}")
    }

    @Test
    fun theLegoIsDeclaredInTheOneVocabulary() {
        // An undeclared lego renders as a bare header and mates with nothing — declaration is what
        // makes it real on the surface, so this is the first thing that must hold.
        val c = assertNotNull(LcncContracts.find(SubVmLegos.MODULES), "vm.modules is not in the palette")
        assertTrue(c.inputs.isEmpty(), "an audit lego must take no input; nothing may steer it")
        assertEquals(listOf("modules", "count"), c.outputs)
        assertEquals("json", c.outputKinds["modules"])
        assertFalse(c.isSource, "vm.modules must not auto-fire; an audit is asked for")
    }

    @Test
    fun itEnumeratesTheInstalledModulesWithoutMountingThem() {
        val before = GuestModules.mounted().toSet()
        val obj = run(emptyMap())
        val rows = rows(obj)
        assertTrue(rows.isNotEmpty(), "expected at least the corenlp module: $obj")
        // Listing must not be a side effect: an audit that mounts what it reports is not an audit.
        assertEquals(before, GuestModules.mounted().toSet(), "vm.modules mounted something by listing")
        val names = rows.map { it["module"].toString() }
        assertTrue("corenlp" in names, "corenlp absent from $names")
    }

    @Test
    fun itReportsJarCountAndManifestPresencePerModule() {
        val obj = run(mapOf("module" to "corenlp"))
        val rows = rows(obj)
        assertEquals(1, rows.size, "the module param must narrow to one row")
        val row = rows.single()
        assertEquals("corenlp", row["module"])
        assertEquals(true, row["manifest"], "corenlp was resolved by utils/subvm and has a manifest")
        assertTrue(num(row["jars"]) > 0, "jar count should be non-zero: $row")
        assertTrue(num(row["bytes"]) > 0L)
        // Not verified unless asked: hashing 472MB is an audit, not a listing.
        assertTrue(!row.containsKey("verified"), "verify must be opt-in: $row")
    }

    @Test
    fun verificationIsOptInAndReportsTheRealResult() {
        val obj = run(mapOf("module" to "corenlp", "verify" to "true"))
        val row = rows(obj).single()
        assertEquals(true, row["verified"], "installed corenlp reported as drifted: ${row["problems"]}")
        assertTrue(num(row["checked"]) > 0, "verify checked nothing: $row")
    }

    @Test
    fun anUnknownModuleIsAnEmptyResultNotAnError() {
        // The worst a wrong param can do. No throw, no partial state, nothing mutated.
        val obj = run(mapOf("module" to "no-such-module"))
        assertEquals(0, rows(obj).size)
    }

    @Test
    fun theAuditNamesWhereItLookedAndTheMountLifecycle() {
        val obj = run(emptyMap())
        assertTrue(obj["root"].toString().endsWith("utils/subvm"), "root should name the modules dir: $obj")
        assertEquals("OPEN", obj["lifecycle"], "mount supervisor should be OPEN while the daemon runs")
    }

    private fun assertFalse(b: Boolean, m: String) = assertTrue(!b, m)
}
