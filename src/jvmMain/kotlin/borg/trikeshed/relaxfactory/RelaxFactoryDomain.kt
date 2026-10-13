package borg.trikeshed.relaxfactory

import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reify
import borg.trikeshed.couch.ViewServer
import java.util.IdentityHashMap

class CouchRevision(val id: String?, val revision: String?)

/** RF persistence uses the same Couch revision and CAS store as the HTTP and replication lanes. */
class RelaxFactoryDomain(val store: RelaxStore, val dyninvoke: ConfixDynInvoke, viewServer: ViewServer = ViewServer()) {
    val revisions = IdentityHashMap<ConfixBean, CouchRevision>()
    val clientRevisions = linkedMapOf<String, String?>()
    val views = ViewRoute(store.viewDocs, viewServer)

    fun create(): ConfixBean = ConfixBean.from(emptyMap()).also { revisions[it] = CouchRevision(null, null) }

    fun get(id: String): ConfixBean? = store.get(id)?.let { body ->
        ConfixBean.from(body).also { bean ->
            revisions[bean] = CouchRevision(id, if (clientRevisions.containsKey(id)) clientRevisions[id] else body["_rev"] as? String)
        }
    }

    fun persist(document: ConfixBean): ConfixBean {
        val id = dyninvoke.get(document, "_id", String::class.java) as? String
            ?: throw IllegalArgumentException("A Couch document requires an id")
        require(id.isNotEmpty()) { "A Couch document requires an id" }
        val prior = revisions.getOrPut(document) { CouchRevision(id, dyninvoke.get(document, "_rev", String::class.java) as? String) }
        require(prior.id == null || prior.id == id) { "A persistent Couch id is immutable" }
        val body = document.fields().filterKeys { it != "_id" && it != "_rev" }
        val receipt = store.put(id, jsonOf(body), prior.revision)
        require(receipt["ok"] == true) { receipt["error"]?.toString() ?: "Couch persistence failed" }
        val rev = receipt["rev"] as String
        dyninvoke.set(document, "_rev", rev)
        revisions[document] = CouchRevision(id, rev)
        return document
    }

    fun delete(document: ConfixBean): Boolean {
        val id = dyninvoke.get(document, "_id", String::class.java) as? String
            ?: throw IllegalArgumentException("A Couch document requires an id")
        val prior = revisions[document] ?: throw IllegalArgumentException("A Couch document must be loaded before deletion")
        require(prior.id == id) { "A persistent Couch id is immutable" }
        val receipt = store.delete(id, prior.revision)
        require(receipt["ok"] == true) { receipt["error"]?.toString() ?: "Couch deletion failed" }
        val rev = receipt["rev"] as String
        dyninvoke.set(document, "_rev", rev)
        revisions[document] = CouchRevision(id, rev)
        return true
    }

    fun storedView(design: String, view: String, query: String): String {
        val reply = views.handle(if (design.startsWith("_design/")) design else "_design/$design", view, CouchHttpSurface.parseQuery(query))
        require(reply.status == 200) { reply.json["error"]?.toString() ?: "Couch view failed" }
        return jsonOf(reply.json)
    }

    fun call(operation: String, document: ConfixBean, arguments: String): String {
        val values = reify(arguments) as? List<*> ?: throw IllegalArgumentException("Script arguments must be a JSON array")
        return jsonOf(ConfixTypes.document(dyninvoke.call(operation, document, *values.toTypedArray())))
    }

    fun clear() {
        revisions.clear()
        clientRevisions.clear()
    }
}
