package borg.trikeshed.vm

import borg.trikeshed.lib.get
import borg.trikeshed.pointcut.VmFacet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** The common contract bound to tier 1 (in-process Graal DAG leafs under the Hypervisor). */
class HypervisorVmHostContractTest : VmHostContract() {
    override fun host(): VmHost = HypervisorVmHost()
}

class JvmVmProvidersTest {
    @Test fun rootProfileSnapshotsAreBoundedAndDetachedFromLaterGuestCalls() {
        HypervisorVmHost().use { host ->
            val guest = host.spawn(VmSpec("profiles", VmFacet.GRAAL_JS))
            guest.eval((0..11).joinToString("\n") { "function root$it() { return $it; }; root$it();" }, "profiles.js")
            val snapshot = guest.pointcuts()!!
            val before = snapshot.toMap()
            assertEquals(VmPointcuts.LIMIT, (before["roots"] as List<*>).size)
            assertTrue((before["omitted"] as Int) > 0)
            guest.call("root0")
            assertEquals(before, snapshot.toMap(), "snapshot must not alias the mutable trainer profiles")
            val current = guest.pointcuts()!!.toMap()["roots"] as List<*>
            assertEquals(2L, current.map { it as Map<*, *> }.single { it["root"] == "root0" }["calls"])
        }
    }

    @Test
    fun jvmOffersInProcessThenProcessTiers() {
        val ids = platformVmProviders().map { it.id }
        assertEquals(listOf("graal-hypervisor", "jvm-process-isolate"), ids)
        val reports = VmSupervisor.reports
        assertEquals(ids, reports.map { it.providerId })
        assertTrue(reports.first().available, "Graal is on the jvmTest classpath: ${reports.first().note}")
        assertEquals("in-process", reports.first().sandboxKind)
        assertEquals("process", reports[1].sandboxKind)
    }

    @Test
    fun supervisorBindsTheGraalHostAndRowsCarryTheTier() {
        VmSupervisor.reset()
        val host = VmSupervisor.current
        assertEquals("jvm", host.platform)
        val h = host.spawn(VmSpec("t", VmFacet.GRAAL_JS))
        assertEquals("in-process", h.tier)
        assertEquals(Teleported.Str("ok"), h.eval("'o' + 'k'"))
        val row = host.rows()[0]
        assertEquals("in-process", row[3].a)
        host.revoke("t", "done")
        assertFalse(h.isAlive)
        assertEquals("revoked", host.rows()[0][4].a)
        assertFails { h.eval("1 + 1") }
        host.revoke("t", "already closed")
        host.close()
        VmSupervisor.reset()
    }

    @Test
    fun untrustedSpawnIsFencedBehindAProcess() {
        val host = HypervisorVmHost()
        val h = host.spawn(VmSpec("p", VmFacet.GRAAL_JS, trust = VmTrust.UNTRUSTED))
        assertEquals("process", h.tier)
        assertEquals(Teleported.Num(6), h.eval("2*3"))
        assertEquals("fenced", host.rows()[0][4].a)
        host.close()
    }
}
