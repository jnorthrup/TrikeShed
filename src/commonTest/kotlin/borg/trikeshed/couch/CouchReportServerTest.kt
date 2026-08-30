package borg.trikeshed.couch

import borg.trikeshed.job.CasStore
import borg.trikeshed.relaxfactory.RelaxTransport
import borg.trikeshed.relaxfactory.RequestFactoryProxy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ReportServer half of spec C3: *a request resolves to a report, CCEK-owned and
 * pointcut-observable*.
 *
 * [CouchReportEvent.MapEmitted] and [CouchReportEvent.Reduced] were declared with the element and
 * had **no producer anywhere in the tree** — the only thing ever reaching the bus was `Committed`,
 * from `CouchChangesFactElement`. So a view could run without the report bus knowing a report had
 * been executed at all. These tests hold that shut.
 */
class CouchReportServerTest {

    private fun docs(vararg pairs: Pair<String, Map<String, Any?>>): List<Document> =
        pairs.map { (id, body) -> Document(id, body.entries.map { Field(it.key, it.value!!) }) }

    private val widgets = docs(
        "a" to mapOf("type" to "widget", "qty" to 2),
        "b" to mapOf("type" to "widget", "qty" to 5),
        "c" to mapOf("type" to "gadget", "qty" to 7),
    )

    private fun countView(reduce: ReduceFunction? = null) = ViewDefinition(
        "_design/rf", "by_type",
        MapFunction.Emit(KeyExpr.DocField("type"), ValueExpr.DocField("qty")),
        reduce,
    )

    @Test
    fun mapAndReduceBecomeFactsOnTheReportBus() = runTest {
        val report = CouchReportReactorElement()
        report.open()

        ViewServer(report).execute(countView(ReduceFunction.Builtin("_sum")), widgets)

        val state = report.reportState.value
        assertEquals(3L, state.mapEmissions, "one MapEmitted per emitted row")
        assertEquals(1L, state.reductions, "one Reduced per reduce")
        assertEquals("_design/rf/by_type", state.lastViewName)

        val seen = report.events.replayCache
        val emitted = seen.filterIsInstance<CouchReportEvent.MapEmitted>()
        assertEquals(listOf("a", "b", "c"), emitted.map { it.docId })
        assertEquals(listOf("widget", "widget", "gadget"), emitted.map { it.key })
        // The reduction reports how many rows the report came out as, not how many went in.
        assertEquals(2L, seen.filterIsInstance<CouchReportEvent.Reduced>().single().count)
    }

    @Test
    fun aMapOnlyViewReportsEmissionsAndNoReduction() = runTest {
        val report = CouchReportReactorElement()
        report.open()
        ViewServer(report).execute(countView(), widgets)
        assertEquals(3L, report.reportState.value.mapEmissions)
        assertEquals(0L, report.reportState.value.reductions, "no reducer ⇒ no Reduced fact")
    }

    @Test
    fun anUnobservedServerStaysSilentAndCostsNothing() = runTest {
        val report = CouchReportReactorElement()
        report.open()
        // The default server has no element: the same view runs, the bus learns nothing.
        ViewServer().execute(countView(ReduceFunction.Builtin("_sum")), widgets)
        assertEquals(CouchReportState(), report.reportState.value)
    }

    @Test
    fun bothAskersReportThroughTheRouterMountedElement() = runTest {
        val report = CouchReportReactorElement()
        report.open()
        val cas = CasStore.inMemory()
        val db = CouchDatabase("trikeshed", CouchStoreFactory.casBacked(cas), cas)
        db.put("a", mapOf("type" to "widget", "qty" to 2), null)
        db.put("b", mapOf("type" to "gadget", "qty" to 7), null)
        db.put(
            "_design/rf",
            mapOf("views" to mapOf("by_type" to mapOf("map" to mapOf("key" to "type", "value" to "qty")))),
            null,
        )
        val router = CouchWireRouter(db, "projects/trikeshed/", report = report)

        // Asker 1: the `_view` route.
        val before = report.reportState.value.mapEmissions
        assertEquals(200, router.handle("GET", "/trikeshed/_design/rf/_view/by_type", ByteArray(0))!!.status)
        val afterRoute = report.reportState.value.mapEmissions
        assertTrue(afterRoute > before, "the _view route reported nothing")

        // Asker 2: a RequestFactory `query` through the envelope, on the same mount.
        val proxy = RequestFactoryProxy(RelaxTransport.local(router.requestFactory))
        assertTrue(proxy.query(mapOf("name" to "by_type", "key" to "type", "value" to "qty")).ok)
        assertTrue(
            report.reportState.value.mapEmissions > afterRoute,
            "the envelope query reported nothing — the factory is not on the observed engine",
        )
    }
}
