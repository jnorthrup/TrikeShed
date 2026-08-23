package borg.trikeshed.vm

import borg.trikeshed.lib.get
import borg.trikeshed.pointcut.VmFacet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The common contract bound to tier 1 (in-process Graal DAG leafs under the Hypervisor). */
class HypervisorVmHostContractTest : VmHostContract() {
    override fun host(): VmHost = HypervisorVmHost()
}

class JvmVmProvidersTest {
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
