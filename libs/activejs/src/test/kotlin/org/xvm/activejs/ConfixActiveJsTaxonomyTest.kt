package org.xvm.activejs

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import org.xvm.activejs.ccek.PointcutEventProducerImpl
import org.xvm.activejs.ccek.ConfixObservationProducerImpl
import org.xvm.activejs.ccek.ConfixObservationConsumer

/** TDD tests for ConfixActiveJsTaxonomy — Confix wrapper around ActiveJsTaxonomy. */
class ConfixActiveJsTaxonomyTest {

    private fun ccekContext(): kotlinx.coroutines.CoroutineContext {
        val supervisor = NioSupervisor()
        runBlocking { supervisor.open() }
        val pointcutProducer = PointcutEventProducerImpl()
        val confixProducer = ConfixObservationProducerImpl()
        supervisor.register(pointcutProducer)
        supervisor.register(confixProducer)
        return supervisor + pointcutProducer + confixProducer
    }

    @Test
    fun `confix wrapper constructs`() = runBlocking {
        val tax = ActiveJsTaxonomy()
        val confix = ConfixActiveJsTaxonomy(tax)
        assertNotNull(confix)
    }

    @Test
    fun `toBlackboardEntries produces observation entries`() = runBlocking {
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.Test", "method", 0x10, 1, ActiveJsFacet.JsFunction), kotlinx.coroutines.currentCoroutineContext())
        val confix = ConfixActiveJsTaxonomy(tax)
        val entries = confix.toBlackboardEntries()
        assertEquals(1, entries.size)
        assertEquals(ConfixRole.OBSERVATION, entries[0].role)
    }

    @Test
    fun `toBlackboardEntriesSeries returns lazy series`() = runBlocking {
        val tax = ActiveJsTaxonomy()
        repeat(100) { i ->
            tax.register(coordinateRow("pkg.T$i", "m", 0x10, i, ActiveJsFacet.JsFunction), kotlinx.coroutines.currentCoroutineContext())
        }
        val confix = ConfixActiveJsTaxonomy(tax)
        val series = confix.toBlackboardEntriesSeries()
        // Series.view: only first 3 accessed
        val first3 = (0 until 3).map { series.b(it) }
        assertEquals(3, first3.size)
    }

    @Test
    fun `blackboard entry contains all coordinate fields`() = runBlocking {
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.Coords", "method", 0x20, 5, ActiveJsFacet.JsFunction), kotlinx.coroutines.currentCoroutineContext())
        val confix = ConfixActiveJsTaxonomy(tax)
        val entries = confix.toBlackboardEntries()
        val docString = entries[0].doc.toString()
        assertTrue(docString.contains("pkg.Coords#method"))
        assertTrue(docString.contains("20")) // pointcutKind
        assertTrue(docString.contains("5"))  // poolId
        assertTrue(docString.contains("JsFunction"))
    }

    @Test
    fun `emitSaxEvents produces events`() = runBlocking {
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.Sax", "method", 0x10, 1, ActiveJsFacet.JsFunction), kotlinx.coroutines.currentCoroutineContext())
        val confix = ConfixActiveJsTaxonomy(tax)
        val events = mutableListOf<SaxEvent>()
        confix.emitSaxEvents { events.add(it) }
        assertTrue(events.isNotEmpty())
    }

    @Test
    fun `cursor returns taxonomy cursor`() = runBlocking {
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.Cursor", "method", 0x10, 1, ActiveJsFacet.JsFunction), kotlinx.coroutines.currentCoroutineContext())
        val confix = ConfixActiveJsTaxonomy(tax)
        val cursor = confix.cursor()
        assertEquals(1, cursor.size)
    }

    @Test
    fun `facetCursor filters by PointcutFacet`() = runBlocking {
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.Facet", "method", 0x10, 1, ActiveJsFacet.JsFunction), kotlinx.coroutines.currentCoroutineContext())
        val confix = ConfixActiveJsTaxonomy(tax)
        val cursor = confix.facetCursor(PointcutFacet.SymbolName)
        assertNotNull(cursor)
    }

    @Test
    fun `activeJsFacetCursor filters by ActiveJsFacet`() = runBlocking {
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.A", "call", 0x10, 1, ActiveJsFacet.JsFunction), kotlinx.coroutines.currentCoroutineContext())
        tax.register(coordinateRow("pkg.B", "field", 0xA5, 2, ActiveJsFacet.JsProxy), kotlinx.coroutines.currentCoroutineContext())
        val confix = ConfixActiveJsTaxonomy(tax)
        val cursor = confix.activeJsFacetCursor(ActiveJsFacet.JsFunction)
        assertEquals(1, cursor.size)
    }

    @Test
    fun `confixObservations produces reactive stream via CCEK`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        val pointcutCursor = LivePointcutCursorFactory.empty()
        val confix = ConfixActiveJsTaxonomy(tax)
        
        val captured = mutableListOf<BlackBoardEntry>()
        val consumer = object : ConfixObservationConsumer {
            override fun onObservation(entry: BlackBoardEntry) { captured += entry }
        }
        val producer = context.getConfixObservationProducer() as? ConfixObservationProducerImpl
        producer?.registerConsumer(consumer)
        
        pointcutCursor.feed(LivePointcutCursor.PointcutEvent(0, 0, 0x10, "CALL", 0, "pkg.Test.call"), context)
        
        assertEquals(1, captured.size)
        assertEquals(ConfixRole.OBSERVATION, captured[0].role)
    }

    @Test
    fun `roundTrip preserves taxonomy through Confix cycle`() = runBlocking {
        val tax = ActiveJsTaxonomy()
        repeat(3) { i ->
            tax.register(coordinateRow("pkg.RoundTrip$i", "method", 0x10 + i, i, ActiveJsFacet.JsFunction), kotlinx.coroutines.currentCoroutineContext())
        }
        val result = ConfixRoundTrip.roundTrip(tax)
        assertTrue(result.entriesMatchTaxonomy)
        assertTrue(result.allHaveRequiredFields)
        assertEquals(3, result.taxonomySize)
        assertEquals(3, result.blackboardEntryCount)
    }

    private fun coordinateRow(
        owner: String,
        method: String,
        kind: Int,
        poolId: Int,
        facet: ActiveJsFacet,
    ) = ActiveJsTaxonomy.CoordinateRow(
        symbolName = "$owner.$method",
        ownerType = owner,
        methodOrField = method,
        classfileCoord = "$owner#$method",
        cpIndex = poolId,
        descriptor = "()V",
        xvmTypeInfo = "",
        pointcutKind = kind,
        poolId = poolId,
        activeJsFacet = facet,
    )
}
