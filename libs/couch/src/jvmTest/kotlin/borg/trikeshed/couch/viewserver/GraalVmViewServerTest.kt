package borg.trikeshed.couch.viewserver

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import borg.trikeshed.couch.ViewStore
import borg.trikeshed.couch.ConfixDocStoreEntry
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.cursor.Series

class GraalVmViewServerTest {
    @Test
    fun testViewServer() {
        val dummyStore = object : ViewStore {
            override fun define(
                name: String,
                map: (ConfixDocStoreEntry, (String, String) -> Unit) -> Unit,
                reduce: ((String, Series<String>) -> Int)?
            ) {
                // Not doing much
                println("defined $name")
                val mockDoc = object : ConfixDocStoreEntry {
                    override val doc: ConfixDoc = object : ConfixDoc {
                        override fun toString(): String {
                            return "{\"hello\":\"world\"}"
                        }
                    }
                }
                var emittedKey: String? = null
                var emittedVal: String? = null
                map(mockDoc) { k, v ->
                    emittedKey = k
                    emittedVal = v
                }
                assertEquals("world", emittedVal)
            }
        }
        val server = GraalVmViewServer(dummyStore)
        server.defineView("test", "function(doc) { emit('mykey', doc.hello); }")
    }
}
