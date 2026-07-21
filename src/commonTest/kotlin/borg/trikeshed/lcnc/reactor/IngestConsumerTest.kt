package borg.trikeshed.lcnc.reactor

import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.lcnc.ccek.IngestStateElement
import borg.trikeshed.lcnc.isam.LcncDatabase
import borg.trikeshed.lib.emptySeriesOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

class IngestConsumerTest {
    @Test
    fun testConsumerReceivesEntities() = runTest {
        val state = IngestStateElement("test-consumer")
        val consumer = IngestConsumer(state)
        
        val job = launch { consumer.startConsuming() }
        
        val mockNuid = nuid(borg.trikeshed.context.nuid.Capability.Custom("test", "test"), Nonce.RandomBytes(), Subnet.core)
        
        val db = LcncDatabase("test-db", "Test Database", null, emptySeriesOf())
        state.publishEntity(ReactorAction.publishEntity(mockNuid, db))
        state.fanout.close()
        
        job.join()
        
        assertEquals(1, consumer.savedDatabases.size)
        assertEquals("test-db", consumer.savedDatabases[0].id)
    }
}
