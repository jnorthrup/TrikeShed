package borg.trikeshed.relaxfactory

import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.confix.*
import borg.trikeshed.couch.ViewServer
import java.util.IdentityHashMap
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import com.google.web.bindery.requestfactory.shared.*

class CouchRevision(val id: String?, val revision: String?)
class ConfixReference(var value: Any?, var snapshot: Any?, val type: Type)

/** RF persistence uses the same Couch revision and CAS store as the HTTP and replication lanes. */
class RelaxFactoryDomain(val store: RelaxStore, val dyninvoke: ConfixDynInvoke, viewServer: ViewServer = ViewServer()) {
    val revisions = IdentityHashMap<ConfixBean, CouchRevision>()
    val types = IdentityHashMap<ConfixBean, Class<out BaseProxy>>()
    val references = IdentityHashMap<ConfixBean, MutableMap<String, ConfixReference>>()
    val creations = ArrayDeque<Class<out BaseProxy>>()
    val loads = linkedMapOf<String, ArrayDeque<Class<out BaseProxy>>>()
    var invocation: RequestFactoryOperation? = null
    var returnType: Type? = null
    val clientRevisions = linkedMapOf<String, String?>()
    val views = ViewRoute(store.viewDocs, viewServer)

    fun create(): ConfixBean = ConfixBean.from(emptyMap()).also {
        revisions[it] = CouchRevision(null, null)
        types[it] = creations.removeFirst()
    }

    fun get(id: String): ConfixBean? = store.get(id)?.let { body ->
        ConfixBean.from(body).also { bean ->
            revisions[bean] = CouchRevision(id, if (clientRevisions.containsKey(id)) clientRevisions[id] else body["_rev"] as? String)
            val type = loads[id]?.removeFirstOrNull() ?: returnType?.let(RequestFactorySchema::rawType)?.takeIf { BaseProxy::class.java.isAssignableFrom(it) }
            requireNotNull(type) { "No registered Couch receiver type" }
            types[bean] = type.asSubclass(BaseProxy::class.java)
        }
    }

    fun persist(document: ConfixBean): ConfixBean {
        val id = dyninvoke.get(document, "_id", String::class.java) as? String
            ?: throw IllegalArgumentException("A Couch document requires an id")
        require(id.isNotEmpty()) { "A Couch document requires an id" }
        val prior = revisions.getOrPut(document) { CouchRevision(id, dyninvoke.get(document, "_rev", String::class.java) as? String) }
        require(prior.id == null || prior.id == id) { "A persistent Couch id is immutable" }
        val body = fields(document).filterKeys { it != "_id" && it != "_rev" }
        val receipt = store.put(id, body, prior.revision)
        require(receipt["ok"] == true) { receipt["error"]?.toString() ?: "Couch persistence failed" }
        val rev = receipt["rev"] as String
        dyninvoke.set(document, "_rev", rev)
        revisions[document] = CouchRevision(id, rev)
        return document
    }

    fun property(bean: ConfixBean, name: String, type: Type): Any? {
        val raw = dyninvoke.get(bean, name)
        val cache = references.getOrPut(bean) { linkedMapOf() }
        cache[name]?.takeIf { it.snapshot == raw }?.let { return it.value }
        return convert(raw, type).also { cache[name] = ConfixReference(it, raw, type) }
    }

    fun property(bean: ConfixBean, name: String, value: Any?, type: Type) {
        dyninvoke.set(bean, name, value)
        references.getOrPut(bean) { linkedMapOf() }[name] = ConfixReference(value, dyninvoke.get(bean, name), type)
    }

    fun convert(value: Any?, type: Type): Any? {
        if (value == null) return null
        if (type is WildcardType) return convert(value, type.upperBounds.single())
        if (type is ParameterizedType) return when (RequestFactorySchema.rawType(type)) {
            List::class.java -> (value as Iterable<*>).map { convert(it, type.actualTypeArguments[0]) }
            Set::class.java -> (value as Iterable<*>).mapTo(linkedSetOf()) { convert(it, type.actualTypeArguments[0]) }
            Map::class.java -> (value as Map<*, *>).entries.associate { convert(it.key, type.actualTypeArguments[0]) to convert(it.value, type.actualTypeArguments[1]) }
            else -> throw IllegalArgumentException("Unsupported RequestFactory collection type")
        }
        val clazz = RequestFactorySchema.rawType(type)
        if (BaseProxy::class.java.isAssignableFrom(clazz)) {
            val bean = if (value is ConfixBean) value else {
                @Suppress("UNCHECKED_CAST")
                ConfixBean.from(value as? Map<String, Any?> ?: throw IllegalArgumentException("A proxy property requires an object"))
            }
            types[bean] = clazz.asSubclass(BaseProxy::class.java)
            return bean
        }
        return ConfixTypes.coerce(value, clazz)
    }

    fun fields(bean: ConfixBean, path: MutableSet<ConfixBean> = java.util.Collections.newSetFromMap(IdentityHashMap())): Map<String, Any?> {
        require(path.add(bean)) { "Couch document graph is cyclic" }
        try {
            val raw = bean.fields()
            val fields = raw.mapValuesTo(linkedMapOf()) { document(it.value, path) }
            for ((name, reference) in references[bean].orEmpty()) {
                if (raw[name] == reference.snapshot) fields[name] = document(reference.value, path)
            }
            return fields
        } finally { path.remove(bean) }
    }

    fun document(value: Any?, path: MutableSet<ConfixBean>): Any? = when (value) {
        is ConfixBean -> fields(value, path)
        is Map<*, *> -> value.entries.associate { it.key to document(it.value, path) }
        is Iterable<*> -> value.map { document(it, path) }
        else -> ConfixTypes.document(value)
    }

    /** Keep RF object identity while presenting the current graph to a live indexed script receiver. */
    fun materialize(value: Any?) {
        when (value) {
            is ConfixBean -> {
                val graph = fields(value)
                refresh(value)
                if (jsonOf(graph) != jsonOf(value.fields())) value.replace(graph)
                val raw = value.fields()
                for ((name, reference) in references[value].orEmpty()) reference.snapshot = raw[name]
            }
            is Map<*, *> -> value.values.forEach(::materialize)
            is Iterable<*> -> value.forEach(::materialize)
        }
    }

    fun refresh(value: Any?) {
        when (value) {
            is ConfixBean -> {
                val raw = value.fields()
                for ((name, reference) in references[value].orEmpty()) {
                    if (raw[name] != reference.snapshot) {
                        reference.value = refresh(reference.value, raw[name], reference.type)
                        reference.snapshot = raw[name]
                    } else refresh(reference.value)
                }
            }
            is Map<*, *> -> value.values.forEach(::refresh)
            is Iterable<*> -> value.forEach(::refresh)
        }
    }

    fun refresh(prior: Any?, value: Any?, type: Type): Any? {
        if (type is WildcardType) return refresh(prior, value, type.upperBounds.single())
        if (prior is ConfixBean && value is Map<*, *>) {
            val proxy = RequestFactorySchema.rawType(type)
            if (ValueProxy::class.java.isAssignableFrom(proxy) ||
                EntityProxy::class.java.isAssignableFrom(proxy) && prior.get("_id", String::class.java) != value["_id"]) return convert(value, type)
            @Suppress("UNCHECKED_CAST")
            prior.replace(value as Map<String, Any?>)
            refresh(prior)
            return prior
        }
        if (type is ParameterizedType && prior is Iterable<*> && value is Iterable<*>) {
            val elementType = type.actualTypeArguments[0]
            val proxy = RequestFactorySchema.rawType(elementType)
            if (ValueProxy::class.java.isAssignableFrom(proxy)) return convert(value, type)
            val old = prior.toList()
            val list = value.mapIndexed { index, child ->
                val receiver = if (EntityProxy::class.java.isAssignableFrom(proxy)) {
                    val id = (child as? Map<*, *>)?.get("_id")
                    old.filterIsInstance<ConfixBean>().firstOrNull { id != null && it.get("_id", String::class.java) == id }
                } else old.getOrNull(index)
                refresh(receiver, child, elementType)
            }
            return if (RequestFactorySchema.rawType(type) == Set::class.java) list.toCollection(linkedSetOf()) else list
        }
        if (type is ParameterizedType && prior is Map<*, *> && value is Map<*, *>) return value.entries.associate { (key, child) ->
            val typedKey = convert(key, type.actualTypeArguments[0])
            typedKey to refresh(prior[typedKey], child, type.actualTypeArguments[1])
        }
        return convert(value, type)
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
        val indexed = confixDoc(arguments)
        val values = ConfixBean.reify(requireNotNull(indexed.root) { "Script arguments must be a JSON array" }, indexed.src) as? List<*>
            ?: throw IllegalArgumentException("Script arguments must be a JSON array")
        return jsonOf(ConfixTypes.document(dyninvoke.call(operation, document, *values.toTypedArray())))
    }

    fun clear() {
        revisions.clear()
        types.clear()
        references.clear()
        creations.clear()
        loads.clear()
        invocation = null
        returnType = null
        clientRevisions.clear()
    }
}
