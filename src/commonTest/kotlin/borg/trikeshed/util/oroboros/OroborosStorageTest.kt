package borg.trikeshed.util.oroboros

import borg.trikeshed.couch.CouchStore
import borg.trikeshed.job.CasStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OroborosStorageTest {

    @Test
    fun testTypedFacetAccess() {
        val testCasStore = CasStore.inMemory()
        val testGateway = CouchAttachmentGateway(CouchStore(null, false), testCasStore)
        val testChannel = Channel<ByteArray>(8)

        val row = object : OroborosStorageRow {
            override fun <R> get(key: OroborosStorageK<R>): R {
                @Suppress("UNCHECKED_CAST")
                return when (key) {
                    is OroborosStorageK.Cas -> testCasStore as R
                    is OroborosStorageK.Attachments -> testGateway as R
                    is OroborosStorageK.Events -> testChannel as R
                    is OroborosStorageK.Manifest -> testGateway.manifest() as R
                    is OroborosStorageK.Status -> "OK" as R
                }
            }
        }

        val cas = row[OroborosStorageK.Cas]
        assertTrue(cas === testCasStore)

        val gateway = row[OroborosStorageK.Attachments]
        assertTrue(gateway === testGateway)

        val events = row[OroborosStorageK.Events]
        assertTrue(events === testChannel)

        val status = row[OroborosStorageK.Status]
        assertEquals("OK", status)
    }
}
