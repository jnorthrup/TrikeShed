package borg.trikeshed.lcnc

import borg.trikeshed.graal.subvm.CamelCatalog
import borg.trikeshed.graal.subvm.GuestModules
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The lazy palette, read out of the mounted module rather than out of a hand-kept list.
 *
 * These assert against jars that are really on disk — `utils/subvm/camel`, 58 of them —
 * so a re-resolve that changes what Camel ships changes what the palette offers, and the
 * test says so instead of a stale constant quietly disagreeing with the mount.
 */
class CamelCatalogTest {

    private fun requireModule() {
        assertTrue(
            GuestModules.isInstalled(CamelCatalog.MODULE),
            "guest module 'camel' is not installed — run: ./gradlew -p utils/subvm installCamel",
        )
    }

    @Test
    fun eipsComeOutOfTheModelJarNotOutOfAList() {
        requireModule()
        val eips = CamelCatalog.eips()
        // camel-core-model 4.8.5 carries 104 root model documents; the floor is deliberately
        // loose so a minor bump does not fail, but a broken read drops to zero and is caught.
        assertTrue(eips.size >= 90, "expected the model jar's EIP set, got ${eips.size}")
        for (expected in listOf("split", "aggregate", "throttle", "idempotentConsumer", "claimCheck", "tokenizer")) {
            assertTrue(expected in eips, "'$expected' missing from the enumerated EIPs")
        }
    }

    @Test
    fun schemesComeOutOfTheServiceFiles() {
        requireModule()
        val schemes = CamelCatalog.schemes()
        assertTrue(schemes.size >= 20, "expected the mounted module's component schemes, got $schemes")
        for (expected in listOf("direct", "seda", "file", "log", "timer", "bean")) {
            assertTrue(expected in schemes, "'$expected' missing from the enumerated schemes")
        }
    }

    @Test
    fun theAdmissionTableMatchesWhatIsActuallyMounted() {
        requireModule()
        // Drift shows up here rather than at route build: a scheme on disk with no
        // CamelLinkage row would be refused with "not listed", which is correct behaviour
        // and a bad surprise. The table is expected to describe the mount exactly.
        assertEquals(
            emptyList<String>(), CamelCatalog.unlisted(),
            "these schemes are mounted but have no CamelLinkage row",
        )
    }

    @Test
    fun detailIsFetchedPerNameAndIsTheRealModelJson() {
        requireModule()
        val split = assertNotNull(CamelCatalog.eipJson("split"), "no model JSON for 'split'")
        assertTrue(split.contains("org.apache.camel.model.SplitDefinition"), "not the real split model: $split")
        // The oneOf on outputs is the wiring grammar — the closed set of EIPs legal inside a
        // split. Its presence is what makes a generated palette able to type its own cables.
        assertTrue(split.contains("\"oneOf\""), "split's model should carry its oneOf grammar")
        assertNotNull(CamelCatalog.componentJson("file"), "no component JSON for 'file'")
        assertEquals(null, CamelCatalog.eipJson("no-such-eip"), "an absent name is null, not an exception")
    }

    @Test
    fun endpointsAreUsableUrisAndOnlyAdmittedOnes() {
        requireModule()
        val endpoints = CamelCatalog.endpoints()
        assertTrue(endpoints.isNotEmpty(), "the picklist would be empty")
        for (e in endpoints) {
            assertTrue(e.contains(':'), "'$e' is not an endpoint URI — a picklist must fill a usable value")
            assertTrue(CamelLinkage.admits(e), "'$e' would be refused by the gate that offered it")
        }
        assertFalse(endpoints.any { it.startsWith("rest:") }, "rest needs an unmounted provider; do not offer it")
    }

    @Test
    fun theCatalogLegoDefaultsToThePicklistShape() = runTest {
        requireModule()
        // The browser fills a live picklist by POSTing {type, inputs:{}} with NO params and
        // walking outputs.endpoints[]. So the no-param call has to answer with a real list of
        // strings; a JSON-encoded array would arrive as text and the picklist would be empty.
        val out = SubVmLegos.camelCatalog().run(LcncNode("cat", SubVmLegos.CAMEL_CATALOG), emptyMap())
        val endpoints = out["endpoints"]
        assertTrue(endpoints is List<*>, "endpoints must be a real list, got ${endpoints?.let { it::class.simpleName }}")
        assertTrue(endpoints.isNotEmpty(), "no endpoints offered")
        assertTrue(endpoints.all { it is String }, "picklist entries must be strings")
        assertEquals(endpoints.size, out["count"], "count must agree with the list")
        assertEquals(true, out["installed"])
    }

    @Test
    fun theCatalogLegoServesEachKind() = runTest {
        requireModule()
        suspend fun kind(k: String, name: String? = null): Map<String, Any?> {
            val params = buildMap {
                put("kind", k)
                if (name != null) put("name", name)
            }
            return SubVmLegos.camelCatalog().run(LcncNode("cat", SubVmLegos.CAMEL_CATALOG, params = params), emptyMap())
        }

        @Suppress("UNCHECKED_CAST")
        val eips = kind("eip")["endpoints"] as List<String>
        assertTrue("split" in eips, "kind=eip should enumerate EIPs")

        @Suppress("UNCHECKED_CAST")
        val schemes = kind("scheme")["endpoints"] as List<String>
        assertTrue("file" in schemes, "kind=scheme should enumerate components")

        @Suppress("UNCHECKED_CAST")
        val linkages = kind("linkage")["endpoints"] as List<String>
        assertTrue("direct" in linkages && "rest" !in linkages, "kind=linkage is what the gate admits: $linkages")

        val detail = kind("eip", "split")["detail"] as String
        assertTrue(detail.contains("SplitDefinition"), "naming an eip should fetch its JSON")
    }

    @Test
    fun theCamelLegoRefusesANonLocalEndpointAtTheLegoBoundary() = runTest {
        requireModule()
        // The refusal happens before any eval, which is the only place egress can still be
        // declined — once Camel has opened a socket on its own thread pool there is no seam.
        val host = borg.trikeshed.vm.HypervisorVmHost()
        try {
            val runner = SubVmLegos.camel(host)
            val out = runner.run(
                LcncNode("bad", SubVmLegos.CAMEL, params = mapOf("from" to "direct:probe", "to" to "http://example.com")),
                emptyMap(),
            )
            assertEquals(false, out["ok"], "a wire endpoint must be refused")
            val error = out["error"] as? String ?: ""
            assertTrue(error.contains("to='http://example.com'"), "the refusal must name the offending port: $error")
            assertEquals("", out["routed"], "nothing was routed")
        } finally {
            host.close()
        }
    }

    @Test
    fun theCamelPaletteEntryPointsItsPicklistsAtTheCatalog() {
        val camel = assertNotNull(LcncContracts.find(SubVmLegos.CAMEL), "vm.camel is not in the palette")
        val catalog = assertNotNull(LcncContracts.find(SubVmLegos.CAMEL_CATALOG), "vm.camel.catalog is not in the palette")
        for (p in listOf("from", "to")) {
            val spec = assertNotNull(camel.params[p], "vm.camel has no '$p' param")
            // SCOPED, not global: the spec carries the node's own module and reach, so the
            // list the editor offers is the list the gate will admit. An unscoped spec here
            // would be the flat dump this design exists to avoid.
            assertTrue(spec.optsFrom.startsWith("${SubVmLegos.CAMEL_CATALOG}?"), "'$p' should fill from the catalog")
            assertTrue(spec.optsFrom.endsWith("#endpoints[]"), "'$p' should walk endpoints[]: ${spec.optsFrom}")
            for (ref in listOf("module=\$module", "reach=\$reach")) {
                assertTrue(ref in spec.optsFrom, "'$p' picklist is not scoped by $ref: ${spec.optsFrom}")
            }
        }
        // Every param the spec dereferences has to exist on this node, or the scoping resolves
        // to blank and the picklist silently widens back to the default.
        for (referenced in listOf("module", "reach")) {
            assertNotNull(camel.params[referenced], "picklist scoping references a param vm.camel does not declare")
        }
        // The resolver runs the named type with no params, so the path it walks has to be a
        // declared output of that type or the picklist silently resolves to nothing.
        assertTrue("endpoints" in catalog.outputs, "the catalog must declare the output the picklist walks")
    }

    // ── departments ────────────────────────────────────────────────────────────

    private val department = "camel-mail"

    private fun requireDepartment() {
        assertTrue(
            GuestModules.isInstalled(department),
            "guest module '$department' is not installed — run: ./gradlew -p utils/subvm installCamelMail",
        )
    }

    @Test
    fun theCatalogReadsTheWholeChainNotJustTheDepartment() {
        requireDepartment()
        val schemes = CamelCatalog.schemes(department)
        assertTrue("smtp" in schemes && "imaps" in schemes, "the department's own schemes are missing: $schemes")
        assertTrue("direct" in schemes && "file" in schemes, "the spine's schemes should come through the chain")
        // EIPs live in the spine's model jar; a department that reported none would be
        // describing its own 4 jars rather than what a mount of it actually resolves.
        assertTrue("split" in CamelCatalog.eips(department), "the chain should carry the spine's EIPs")
        assertEquals(listOf(department, "camel"), CamelCatalog.mounted(department))
    }

    @Test
    fun theDepartmentIsListedAsOneAndTheSpineIsNot() {
        requireDepartment()
        val departments = CamelCatalog.departments()
        assertTrue(department in departments, "the department should be listed: $departments")
        assertFalse("camel" in departments, "the spine extends nothing and is not a department")
    }

    @Test
    fun theAdmissionTableMatchesTheDepartmentMountToo() {
        requireDepartment()
        assertEquals(
            emptyList<String>(), CamelCatalog.unlisted(department),
            "these schemes are mounted through the department chain but have no CamelLinkage row",
        )
    }

    @Test
    fun reachDecidesWhetherTheDepartmentsEndpointsAreOffered() {
        requireDepartment()
        val local = CamelCatalog.endpoints(department, CamelLinkage.Reach.LOCAL)
        assertTrue(local.none { it.startsWith("smtp") || it.startsWith("imap") || it.startsWith("pop3") },
            "a LOCAL node must not be offered a wire endpoint it would then be refused: $local")

        val full = CamelCatalog.endpoints(department, CamelLinkage.Reach.DEPARTMENT)
        assertTrue(full.any { it.startsWith("imaps://") }, "DEPARTMENT should offer the mail endpoints: $full")
        // Whatever is offered must be admissible under the same reach and chain, or the editor
        // is filling a value the gate will reject.
        val chain = CamelCatalog.mounted(department)
        for (e in full) {
            assertTrue(CamelLinkage.admits(e, CamelLinkage.Reach.DEPARTMENT, chain), "'$e' would be refused")
        }
    }

    @Test
    fun aScopedPicklistStaysShortEnoughToUse() {
        requireDepartment()
        // The whole reason departments exist as a vocabulary and not just as packaging: the
        // list an operator actually sees is bounded by what they mounted, not by what Camel
        // ships. If this ever fails, the palette has gone back to being a flat dump.
        for (reach in CamelLinkage.Reach.entries) {
            val offered = CamelCatalog.endpoints(department, reach)
            assertTrue(offered.size <= 40, "picklist has ${offered.size} entries — too many to pick from")
        }
    }

    @Test
    fun theCatalogLegoScopesByModuleAndReach() = runTest {
        requireDepartment()
        suspend fun run(vararg params: Pair<String, String>): Map<String, Any?> =
            SubVmLegos.camelCatalog().run(
                LcncNode("cat", SubVmLegos.CAMEL_CATALOG, params = params.toMap()), emptyMap())

        @Suppress("UNCHECKED_CAST")
        val localMail = run("module" to department)["endpoints"] as List<String>
        assertTrue(localMail.none { it.startsWith("smtp") }, "reach defaults to LOCAL: $localMail")

        @Suppress("UNCHECKED_CAST")
        val wireMail = run("module" to department, "reach" to "DEPARTMENT")["endpoints"] as List<String>
        assertTrue(wireMail.any { it.startsWith("smtp") }, "reach=DEPARTMENT should offer smtp: $wireMail")

        assertEquals(listOf(department, "camel"), run("module" to department)["mounted"])

        @Suppress("UNCHECKED_CAST")
        val depts = run("kind" to "department")["endpoints"] as List<String>
        assertTrue(department in depts, "kind=department should list the installed departments: $depts")
    }

    @Test
    fun theCamelLegoRefusesADepartmentSchemeUntilBothGatesOpen() = runTest {
        requireDepartment()
        val host = borg.trikeshed.vm.HypervisorVmHost()
        try {
            val runner = SubVmLegos.camel(host)
            suspend fun attempt(vararg extra: Pair<String, String>): Map<String, Any?> = runner.run(
                LcncNode("mail", SubVmLegos.CAMEL, params = mapOf(
                    "from" to "direct:probe", "to" to "smtp://mail.example.com?to=x") + extra.toMap()),
                emptyMap(),
            )
            // Gate one: the module is not mounted for this node.
            val unmounted = attempt()["error"] as? String ?: ""
            assertTrue(unmounted.contains("camel-mail"), "should name the department to mount: $unmounted")
            // Gate two: mounted, but the node has not declared that it reaches the wire.
            val unreached = attempt("module" to department)["error"] as? String ?: ""
            assertTrue(unreached.contains("reach"), "should point at the reach declaration: $unreached")
        } finally {
            host.close()
        }
    }
}
