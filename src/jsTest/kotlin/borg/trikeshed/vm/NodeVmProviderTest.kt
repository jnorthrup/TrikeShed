package borg.trikeshed.vm

import borg.trikeshed.pointcut.VmFacet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The node tier: `vm.runInNewContext` behind the common contract; the browser provider is reported unavailable here. */
class NodeVmProviderContractTest : VmHostContract() {
    override fun host(): VmHost = NodeVmProvider.open()
}

class JsVmProvidersTest {
    @Test
    fun nodeIsAvailableAndBrowserIsNotUnderNode() {
        val reports = VmSupervisor.reports
        assertEquals(listOf("node-vm", "browser-worker"), reports.map { it.providerId })
        assertTrue(reports[0].available); assertTrue(!reports[1].available)
        val host = VmSupervisor.current
        assertEquals("js-node", host.platform)
        val h = host.spawn(VmSpec("n", VmFacet.GRAAL_JS, budget = VmBudget(wallMillis = 500)))
        assertEquals("node-vm", h.tier)
        assertEquals(Teleported.Arr(listOf(Teleported.Num(2), Teleported.Num(4))), h.eval("[1,2].map(function(x){return x*2})"))
        assertTrue(runCatching { h.eval("while(true){}") }.isFailure, "the wall budget interrupts a spinning guest")
        host.close(); VmSupervisor.reset()
    }
}
