package borg.trikeshed.lcnc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The admission table is the whole egress story for `vm.camel`, so it is tested for the
 * property that matters — that it FAILS CLOSED — rather than for its contents.
 */
class CamelLinkageTest {

    @Test
    fun everyLocalSchemeInTheTableIsAdmitted() {
        val local = CamelLinkage.localSchemes()
        assertTrue(local.isNotEmpty(), "the table admits nothing at all")
        for (s in listOf("direct", "seda", "file", "log", "timer")) {
            assertTrue(s in local, "'$s' is a local linkage and should be admitted: $local")
        }
    }

    @Test
    fun anUnknownSchemeIsNetworkAndRefused() {
        // The point of the default is that a component nobody has written a row for cannot
        // be reached by writing its URI into a param. A new mount is denied until described.
        for (uri in listOf("http://example.com", "kafka:topic", "ftp:host/dir", "netty:tcp://h:1")) {
            assertEquals(CamelLinkage.Locality.NETWORK, CamelLinkage.localityOf(uri), "should be NETWORK: $uri")
            assertFalse(CamelLinkage.admits(uri), "should be refused: $uri")
            assertNotNull(CamelLinkage.refusal(uri), "a refusal must say why: $uri")
        }
    }

    @Test
    fun schemesNeedingAnUnmountedProviderAreRefused() {
        // rest/rest-api are shipped by the mounted module but cannot start without a
        // platform-http provider, so admitting them would trade a clear refusal for a
        // confusing route-build failure.
        assertFalse(CamelLinkage.admits("rest:get:/x"), "rest needs a provider that is not mounted")
        assertTrue(CamelLinkage.refusal("rest:get:/x")!!.contains("provider"), "the reason should name the provider")
    }

    @Test
    fun admittedSchemesProduceNoRefusal() {
        for (uri in listOf("direct:lcnc", "seda:work", "file:.lcnc/in?noop=true", "log:out", "timer:t?period=1000")) {
            assertNull(CamelLinkage.refusal(uri), "should be admitted: $uri")
        }
    }

    @Test
    fun aUriWithoutASchemeIsRefusedRatherThanGuessed() {
        assertNotNull(CamelLinkage.refusal("lcnc"), "a bare word is not an endpoint URI")
        assertNotNull(CamelLinkage.refusal(""), "blank is not an endpoint URI")
        assertEquals("", CamelLinkage.schemeOf("lcnc"))
    }

    @Test
    fun schemeParsingIsCaseInsensitiveAndIgnoresTheRemainder() {
        assertEquals("direct", CamelLinkage.schemeOf("DIRECT:Lcnc"))
        assertEquals("file", CamelLinkage.schemeOf("file:.lcnc/in?noop=true&delay=5"))
        assertTrue(CamelLinkage.admits("SEDA:work"))
    }

    @Test
    fun theTableHasNoDuplicateSchemes() {
        val schemes = CamelLinkage.known.map { it.scheme }
        assertEquals(schemes.size, schemes.toSet().size, "a duplicated row makes one of them unreachable")
    }

    // ── departments ────────────────────────────────────────────────────────────

    private val spineOnly = listOf(CamelLinkage.SPINE)
    private val withMail = listOf("camel-mail", CamelLinkage.SPINE)

    @Test
    fun aDepartmentSchemeIsNotEvenNameableUntilItsModuleIsMounted() {
        // PROVISION is the first gate and it needs no policy: with the spine alone there is
        // no MailComponent on any classpath, so the refusal names the module to mount.
        val why = assertNotNull(CamelLinkage.refusal("smtp://host?to=x", CamelLinkage.Reach.DEPARTMENT, spineOnly))
        assertTrue(why.contains("camel-mail"), "the refusal should name the module that ships it: $why")
        assertTrue(why.contains("installCamelMail"), "and the task that installs it: $why")
    }

    @Test
    fun mountingADepartmentIsNotByItselfPermissionToReachTheWire() {
        // REACH is the second, narrower gate: mounting for an IMAP poller would otherwise
        // make SMTP nameable from every other route on the box.
        val why = assertNotNull(CamelLinkage.refusal("smtp://host?to=x", CamelLinkage.Reach.LOCAL, withMail))
        assertTrue(why.contains("reach"), "the refusal should point at the reach declaration: $why")
        assertNull(
            CamelLinkage.refusal("smtp://host?to=x", CamelLinkage.Reach.DEPARTMENT, withMail),
            "mounted plus reach=DEPARTMENT is the admitting combination",
        )
    }

    @Test
    fun localSchemesStayLocalEvenWithADepartmentMounted() {
        val local = CamelLinkage.admissible(CamelLinkage.Reach.LOCAL, withMail)
        assertTrue("direct" in local, "the spine's local schemes are unaffected by a mount")
        assertTrue(local.none { it in setOf("smtp", "imap", "pop3") }, "LOCAL must not admit mail: $local")

        val full = CamelLinkage.admissible(CamelLinkage.Reach.DEPARTMENT, withMail)
        assertTrue("smtp" in full && "imaps" in full, "DEPARTMENT admits what the department ships: $full")
        assertTrue("direct" in full, "and still admits the spine")
    }

    @Test
    fun everyRowNamesTheModuleThatShipsIt() {
        for (row in CamelLinkage.known) {
            assertTrue(row.module.isNotBlank(), "'${row.scheme}' has no module")
        }
        assertEquals("camel-mail", CamelLinkage.moduleOf("imaps://h"), "mail schemes belong to the department")
        assertEquals(CamelLinkage.SPINE, CamelLinkage.moduleOf("direct:x"), "core schemes belong to the spine")
        assertEquals(null, CamelLinkage.moduleOf("kafka:t"), "an unknown scheme belongs to nothing")
    }
}
