import borg.trikeshed.couch.*
import borg.trikeshed.lib.*

fun main() {
    val store = CouchStoreFactory.inMemory()
    val doc = Document("doc1", listOf(Field("name", "Alice")))
    store.put(doc)
    val head = store.head
    val retrievedRev = head.getRev("doc1")
    println("Rev: " + retrievedRev)
    val retrievedDoc = head.get("doc1")
    val q = head.query()
    println("Doc: " + retrievedDoc)
    println("Q a: " + q.cursor.a)
    for (i in 0 until q.cursor.a) {
        val row = q.cursor.b(i)
        println(" Row " + i + ": _id=" + row.b(0).a + ", _rev=" + row.b(1).a + ", name=" + row.b(2).a)
    }
}
