package org.xvm.activejs.ccek

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import org.xvm.activejs.BlackBoardEntry
import org.xvm.activejs.ConfixRole

/** TDD tests for CCEK Pointcut SPI Elements */
class PointcutCcekElementsTest {

    @Test
    fun `pointcut event producer registers in NioSupervisor`() = runBlocking {
        val supervisor = NioSupervisor()
        supervisor.open()
        
        val producer = PointcutEventProducerImpl()
        supervisor.register(producer)
        
        val found = supervisor.service<PointcutEventProducer>()
        assertNotNull(found)
        assertEquals(producer, found)
    }

    @Test
    fun `pointcut event producer emits to registered consumers`() = runBlocking {
        val supervisor = NioSupervisor()
        supervisor.open()
        
        val producer = PointcutEventProducerImpl()
        supervisor.register(producer)
        
        val captured = mutableListOf<FieldSynapse>()
        val consumer = object : PointcutEventConsumer {
            override fun onEvent(synapse: FieldSynapse) { captured += synapse }
        }
        producer.registerConsumer(consumer)
        
        val synapse = FieldSynapse(
            phase = 0,
            opcode = 0xA5.toByte(),
            methodIdx = 1,
            addr = 0x1000,
            seq = 42,
            nano = 123456789L,
            callsiteHash = 0xABCD,
            templateIdx = 0
        )
        producer.emit(synapse)
        
        assertEquals(1, captured.size)
        assertEquals(synapse, captured[0])
    }

    @Test
    fun `pointcut event consumer resolves from context`() = runBlocking {
        val supervisor = NioSupervisor()
        supervisor.open()
        
        val producer = PointcutEventProducerImpl()
        supervisor.register(producer)
        
        val consumer = PointcutEventConsumerImpl(producer)
        producer.registerConsumer(consumer)
        
        val fromContext = supervisor.getPointcutEventConsumer()
        assertNotNull(fromContext)
    }

    @Test
    fun `confix observation producer registers in NioSupervisor`() = runBlocking {
        val supervisor = NioSupervisor()
        supervisor.open()
        
        val producer = ConfixObservationProducerImpl()
        supervisor.register(producer)
        
        val found = supervisor.service<ConfixObservationProducer>()
        assertNotNull(found)
        assertEquals(producer, found)
    }

    @Test
    fun `confix observation producer emits to registered consumers`() = runBlocking {
        val supervisor = NioSupervisor()
        supervisor.open()
        
        val producer = ConfixObservationProducerImpl()
        supervisor.register(producer)
        
        val captured = mutableListOf<BlackBoardEntry>()
        val consumer = object : ConfixObservationConsumer {
            override fun onObservation(entry: BlackBoardEntry) { captured += entry }
        }
        producer.registerConsumer(consumer)
        
        val entry = BlackBoardEntry(confixDoc("{\"test\":true}"), ConfixRole.OBSERVATION)
        producer.emit(entry)
        
        assertEquals(1, captured.size)
        assertEquals(entry, captured[0])
    }

    @Test
    fun `taxonomy observer registers in NioSupervisor`() = runBlocking {
        val supervisor = NioSupervisor()
        supervisor.open()
        
        val observer = TaxonomyObserverImpl()
        supervisor.register(observer)
        
        val found = supervisor.service<TaxonomyObserver>()
        assertNotNull(found)
        assertEquals(observer, found)
    }

    @Test
    fun `taxonomy observer receives row registered notification`() = runBlocking {
        val supervisor = NioSupervisor()
        supervisor.open()
        
        val observer = TaxonomyObserverImpl()
        supervisor.register(observer)
        
        val notified = mutableListOf<org.xvm.activejs.CoordinateRow>()
        val capturingObserver = object : TaxonomyObserverImpl() {
            override fun onRowRegistered(row: org.xvm.activejs.CoordinateRow) { notified += row }
        }
        supervisor.register(capturingObserver)
        
        val row = org.xvm.activejs.CoordinateRow(
            symbolName = "pkg.Test.method",
            ownerType = "pkg.Test",
            methodOrField = "method",
            classfileCoord = "pkg.Test#method",
            cpIndex = 1,
            descriptor = "()V",
            xvmTypeInfo = "",
            pointcutKind = 0x10,
            poolId = 42,
            activeJsFacet = org.xvm.activejs.ActiveJsFacet.JsFunction
        )
        capturingObserver.onRowRegistered(row)
        
        assertEquals(1, notified.size)
        assertEquals(row, notified[0])
    }

    @Test
    fun `unregister consumer removes from producer`() = runBlocking {
        val supervisor = NioSupervisor()
        supervisor.open()
        
        val producer = PointcutEventProducerImpl()
        supervisor.register(producer)
        
        val captured = mutableListOf<FieldSynapse>()
        val consumer = object : PointcutEventConsumer {
            override fun onEvent(synapse: FieldSynapse) { captured += synapse }
        }
        producer.registerConsumer(consumer)
        producer.unregisterConsumer(consumer)
        
        val synapse = FieldSynapse(0, 0xA5.toByte(), 1, 0x1000, 42, 123456789L, 0xABCD, 0)
        producer.emit(synapse)
        
        assertTrue(captured.isEmpty())
    }
}
