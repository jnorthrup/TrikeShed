package borg.trikeshed.couch.viewserver

import borg.trikeshed.couch.ViewStoreFactory
import borg.trikeshed.couch.ViewIndex
import borg.trikeshed.couch.ipfs.IpfsMeshStore
import borg.trikeshed.parse.confix.confixDoc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class IpfsGraalVmViewServerTest {

    @Test
    fun `test ipfs store and graalvm view server integration`() {
        // 1. Setup IPFS Mesh Store
        val ipfsStore = IpfsMeshStore()

        // 2. Insert documents into IPFS Store (content addressed)
        val doc1 = confixDoc("""{"name": "Alice", "age": 30, "type": "user"}""")
        val doc2 = confixDoc("""{"name": "Bob", "age": 25, "type": "user"}""")
        val doc3 = confixDoc("""{"name": "Charlie", "age": 35, "type": "admin"}""")

        ipfsStore.putContent(doc1)
        ipfsStore.putContent(doc2)
        ipfsStore.putContent(doc3)

        assertEquals(3, ipfsStore.backingStore.size)

        // 3. Setup ViewStore mapped to the backing store
        val viewStore = ViewStoreFactory.create(ipfsStore.backingStore)

        // 4. Setup GraalVM ViewServer
        GraalVmViewServer(viewStore).use { viewServer ->
            // 5. Define a view using Javascript (CouchDB map function style)
            val mapJs = """
                function(doc) {
                    if (doc.type === 'user') {
                        emit(doc.name, doc.age);
                    }
                }
            """.trimIndent()

            viewServer.defineView("users_by_name", mapJs)

            // 6. Run the indexer (evaluates the JS on the documents)
            viewStore.reindex()

            // 7. Query the view
            @Suppress("UNCHECKED_CAST")
            val view = viewStore["users_by_name"] as? ViewIndex<String, String, Int>

            assertNotNull(view)
            assertEquals(2, view!!.keyCount) // Only Alice and Bob are 'user'

            val aliceResult = view.forKey("Alice")
            assertEquals(1, aliceResult.count)
            assertEquals("30", aliceResult.rows[0].value)

            val bobResult = view.forKey("Bob")
            assertEquals(1, bobResult.count)
            assertEquals("25", bobResult.rows[0].value)
        }
    }

    @Test
    fun `test view server with reduce function`() {
        val ipfsStore = IpfsMeshStore()
        ipfsStore.putContent(confixDoc("""{"category": "A", "amount": 10}"""))
        ipfsStore.putContent(confixDoc("""{"category": "A", "amount": 20}"""))
        ipfsStore.putContent(confixDoc("""{"category": "B", "amount": 5}"""))

        val viewStore = ViewStoreFactory.create(ipfsStore.backingStore)

        GraalVmViewServer(viewStore).use { viewServer ->
            val mapJs = """
                function(doc) {
                    emit(doc.category, doc.amount);
                }
            """.trimIndent()

            val reduceJs = """
                function(key, values, rereduce) {
                    var sum = 0;
                    for (var i = 0; i < values.length; i++) {
                        // CouchDB passes strings if not careful, ensure number
                        sum += Number(values[i]);
                    }
                    return sum;
                }
            """.trimIndent()

            viewServer.defineView("sum_by_category", mapJs, reduceJs)
            viewStore.reindex()

            @Suppress("UNCHECKED_CAST")
            val view = viewStore["sum_by_category"] as? ViewIndex<String, String, Int>

            assertNotNull(view)

            val resultA = view!!.forKey("A")
            // total property is the output of reduce
            assertEquals(30, resultA.total)

            val resultB = view.forKey("B")
            assertEquals(5, resultB.total)
        }
    }
}
