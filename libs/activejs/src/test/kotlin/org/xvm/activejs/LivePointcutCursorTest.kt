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
import org.xvm.activejs.ccek.PointcutEventConsumer
import org.xvm.activejs.ccek.PointcutEventConsumerImpl
import org.xvm.activejs.ccek.FieldSynapse

/** TDD tests for LivePointcutCursor — reactive cursor projecting live pointcut events from TypedefResolutionSeries journal into ActiveJsTaxonomy. */
class LivePointcutCursorTest {

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob())
    
    private fun ccekContext(): kotlinx.coroutines.CoroutineContext {
        val supervisor = NioSupervisor()
        runBlocking { supervisor.open() }
        val producer = PointcutEventProducerImpl()
        supervisor.register(producer)
        return supervisor + producer
    }

    @Test
    fun `cursor constructs empty`() {
        val cursor = LivePointcutCursorFactory.empty()
        assertNotNull(cursor)
    }

    @Test
    fun `feed pointcut event registers in taxonomy`() = runBlocking {
        val context = ccekContext()
        val cursor = LivePointcutCursorFactory.empty()
        val event = LivePointcutCursor.PointcutEvent(
            seq = 0,
            nano = System.nanoTime(),
            opcode = 0x10,
            phase = "CALL",
            addr = 0,
            method = "pkg.Test.run",
        )
        cursor.feed(event, context)
        assertEquals(1, cursor.taxonomy.size)
    }

    @Test
    fun `feed multiple events registers all`() = runBlocking {
        val context = ccekContext()
        val cursor = LivePointcutCursorFactory.empty()
        repeat(5) { i ->
            cursor.feed(LivePointcutCursor.PointcutEvent(
                seq = i,
                nano = System.nanoTime(),
                opcode = 0x10 + i,
                phase = "CALL",
                addr = i,
                method = "pkg.Test.method$i",
            ), context)
        }
        assertEquals(5, cursor.taxonomy.size)
    }

    @Test
    fun `reactive query receives matching events via CCEK`() = runBlocking {
        val context = ccekContext()
        val cursor = LivePointcutCursorFactory.empty()
        val query = LivePointcutCursor.LiveQuery(kind = 0x10)
        
        val captured = mutableListOf<FieldSynapse>()
        val consumer = object : PointcutEventConsumer {
            override fun onEvent(synapse: FieldSynapse) { captured += synapse }
        }
        // Register consumer with producer
        val producer = context.getPointcutEventProducer() as? PointcutEventProducerImpl
        producer?.registerConsumer(consumer)
        
        cursor.feed(LivePointcutCursor.PointcutEvent(0, 0, 0x10, "CALL", 0, "pkg.A.call"), context)
        cursor.feed(LivePointcutCursor.PointcutEvent(1, 0, 0xA5, "FIELD", 1, "pkg.B.field"), context)
        cursor.feed(LivePointcutCursor.PointcutEvent(2, 0, 0x10, "CALL", 2, "pkg.C.call"), context)
        
        // Filter for 0x10 events
        val matching = captured.filter { it.opcode == 0x10.toByte() }
        assertEquals(2, matching.size)
    }

    @Test
    fun `cold query materializes matching rows`() = runBlocking {
        val context = ccekContext()
        val cursor = LivePointcutCursorFactory.empty()
        cursor.feed(LivePointcutCursor.PointcutEvent(0, 0, 0x10, "CALL", 0, "pkg.A.call"), context)
        cursor.feed(LivePointcutCursor.PointcutEvent(1, 0, 0xA5, "FIELD", 1, "pkg.B.field"), context)
        cursor.feed(LivePointcutCursor.PointcutEvent(2, 0, 0x10, "CALL", 2, "pkg.C.call"), context)

        val series = cursor.coldQuery(LivePointcutCursor.LiveQuery(kind = 0x10))
        assertEquals(2, series.a)
    }

    @Test
    fun `cursorByKind filters by opcode`() = runBlocking {
        val context = ccekContext()
        val cursor = LivePointcutCursorFactory.empty()
        cursor.feed(LivePointcutCursor.PointcutEvent(0, 0, 0x10, "CALL", 0, "pkg.A.call"), context)
        cursor.feed(LivePointcutCursor.PointcutEvent(1, 0, 0xA5, "FIELD", 1, "pkg.B.field"), context)
        cursor.feed(LivePointcutCursor.PointcutEvent(2, 0, 0x10, "CALL", 2, "pkg.C.call"), context)

        val filtered = cursor.cursorByKind(0x10)
        assertEquals(2, filtered.size)
    }

    @Test
    fun `cursorByFacet filters by ActiveJsFacet`() = runBlocking {
        val context = ccekContext()
        val cursor = LivePointcutCursorFactory.empty()
        cursor.feed(LivePointcutCursor.PointcutEvent(0, 0, 0x10, "CALL", 0, "pkg.A.call"), context)   // JsFunction
        cursor.feed(LivePointcutCursor.PointcutEvent(1, 0, 0xA5, "FIELD", 1, "pkg.B.field"), context) // JsProxy
        cursor.feed(LivePointcutCursor.PointcutEvent(2, 0, 0x34, "ALLOC", 2, "pkg.C.init"), context)   // JsPromise

        val functionCursor = cursor.cursorByFacet(ActiveJsFacet.JsFunction)
        assertEquals(1, functionCursor.size)

        val proxyCursor = cursor.cursorByFacet(ActiveJsFacet.JsProxy)
        assertEquals(1, proxyCursor.size)

        val promiseCursor = cursor.cursorByFacet(ActiveJsFacet.JsPromise)
        assertEquals(1, promiseCursor.size)
    }

    @Test
    fun `fromClassFileTaxonomy bridges JVM taxonomy`() = runBlocking {
        // Create a JVM ClassFileTaxonomy with test data
        val jvmTax = ClassFileTaxonomy()
        jvmTax.register(ClassFileTaxonomy.CoordinateRow(
            symbolName = "pkg.Jvm.method",
            ownerType = "pkg.Jvm",
            methodOrField = "method",
            classfileCoord = "pkg.Jvm#method",
            cpIndex = 1,
            descriptor = "()V",
            xvmTypeInfo = "",
            pointcutKind = 0x10,
            poolId = 100,
        ))

        val cursor = LivePointcutCursorFactory.fromClassFileTaxonomy(jvmTax)
        assertEquals(1, cursor.taxonomy.size)
        val row = cursor.taxonomy.rowAt(0)
        assertEquals("pkg.Jvm.method", row.symbolName)
        assertEquals(ActiveJsFacet.JsFunction, row.activeJsFacet)
    }
}
