package borg.trikeshed.couch

import borg.trikeshed.context.ElementState
import borg.trikeshed.pointcut.PointcutReporter
import borg.trikeshed.pointcut.VmFacet
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraalCouchReportServerTest {

    @Test
    fun cascadeMapReduceRunsRealGraalJsOverConfixDocuments() = runBlocking {
        val store = ConfixDocStoreFactory.createSequential()
        store.put("reading-1", readingJson(1_720_742_400_000L, 100.0, 10.0))
        store.put("reading-2", readingJson(1_720_742_460_000L, 200.0, 20.0))
        store.put("reading-3", readingJson(1_720_742_520_000L, 300.0, 30.0))

        val parent = SupervisorJob()
        val server = GraalCouchReportServer.open(
            store = store,
            parentJob = parent,
            reduceChunkSize = 2,
        )
        val reactor = server.reactor

        try {
            val report = server.report(
                view = CouchCascadeView.BY_MACHINE,
                startKey = listOf("machine-7", 2024, 7, 12, 0, 0),
                endKey = listOf("machine-7", 2024, 7, 12, 0, 2),
            )

            assertEquals(3L, report.count)
            assertEquals(600.0, report.metrics.getValue("cpu_mhz").sum)
            assertEquals(200.0, report.metrics.getValue("cpu_mhz").avg)
            assertEquals(100.0, report.metrics.getValue("cpu_mhz").min)
            assertEquals(300.0, report.metrics.getValue("cpu_mhz").max)
            assertEquals(20.0, report.metrics.getValue("memory_mib").avg)

            val state = reactor.reportState.value
            assertEquals(3L, state.mapEmissions)
            assertEquals(1L, state.reductions)
            assertTrue(state.pointcuts >= 4L)
            assertTrue(
                reactor.events.replayCache
                    .filterIsInstance<CouchReportEvent.PointcutObserved>()
                    .any { it.vmFacet == VmFacet.GRAAL_JS.id },
            )
            assertEquals(ElementState.ACTIVE, reactor.lifecycleState)
        } finally {
            server.close()
        }

        assertEquals(ElementState.CLOSED, reactor.lifecycleState)
        assertTrue(parent.isActive, "closing report server must not cancel its parent")
        parent.cancel()
    }

    @Test
    fun classfileReporterFeedsTheSameSupervisorMappedReactor() = runBlocking {
        val parent = SupervisorJob()
        val server = GraalCouchReportServer.open(
            store = ConfixDocStoreFactory.createSequential(),
            parentJob = parent,
        )
        val reactor = server.reactor

        try {
            PointcutReporter.report(
                VmFacet.JVM.id,
                "borg.trikeshed.couch.ReportField.value",
                null,
                "value",
                42,
            )

            val event = reactor.events.replayCache
                .filterIsInstance<CouchReportEvent.PointcutObserved>()
                .last()
            assertEquals(VmFacet.JVM.id, event.vmFacet)
            assertEquals("borg.trikeshed.couch.ReportField.value", event.coordinate)
            assertEquals("value", event.propertyName)
        } finally {
            server.close()
            parent.cancel()
        }
    }

    private fun readingJson(readingDate: Long, cpuMhz: Double, memoryMib: Double): String =
        """{
          "organization_id":"org-2",
          "contract_id":"contract-3",
          "billing_group_id":"billing-5",
          "infrastructure_id":"infra-4",
          "machine_id":"machine-7",
          "reading_id":"$readingDate",
          "interval":60,
          "reading_date":$readingDate,
          "cpu_mhz":$cpuMhz,
          "memory_mib":$memoryMib,
          "storage_gib":100.0,
          "disk_io_kilobytes_per_sec":5.0,
          "lan_io_kilobits_per_sec":6.0,
          "wan_io_kilobits_per_sec":7.0,
          "consumption_wac":8.0,
          "created_at":$readingDate
        }""".trimIndent()
}
