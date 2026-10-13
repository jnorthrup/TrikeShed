package borg.trikeshed.relaxfactory

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.ViewServer
import borg.trikeshed.parse.jsonOf
import com.google.gwt.user.server.Base64Utils
import com.google.web.bindery.autobean.shared.AutoBeanCodex
import com.google.web.bindery.autobean.shared.ValueCodex
import com.google.web.bindery.autobean.vm.AutoBeanFactorySource
import com.google.web.bindery.requestfactory.server.ServiceLayer
import com.google.web.bindery.requestfactory.server.ServiceLayerDecorator
import com.google.web.bindery.requestfactory.server.SimpleRequestProcessor
import com.google.web.bindery.requestfactory.shared.*
import com.google.web.bindery.requestfactory.shared.messages.MessageFactory
import com.google.web.bindery.requestfactory.shared.messages.RequestMessage
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** The only RF domain access is registered metadata over Confix, decorated with Couch dynamics. */
class RelaxFactoryServiceLayer(
    val schema: RequestFactorySchema,
    val domain: RelaxFactoryDomain,
) : ServiceLayerDecorator() {
    val dispatch = linkedMapOf<Method, String>()

    init {
        for ((token, binding) in schema.operations) {
            if (dispatch.containsKey(binding.domain)) continue
            val name = "requestfactory:$token"
            dispatch[binding.domain] = name
            domain.dyninvoke.register(name) { args ->
                when (binding.domain.name) {
                    "get" -> domain.get(args[0] as String)
                    "persist" -> domain.persist(args[0] as ConfixBean)
                    "delete" -> domain.delete(args[0] as ConfixBean)
                    "storedView" -> domain.storedView(args[0] as String, args[1] as String, args[2] as String)
                    "call" -> domain.call(args[0] as String, args[1] as ConfixBean, args[2] as String)
                    else -> throw IllegalArgumentException("Unregistered Couch operation")
                }
            }
        }
    }

    override fun resolveRequestFactory(token: String): Class<out RequestFactory> =
        schema.factories[token] ?: report("Unregistered RequestFactory")

    override fun resolveRequestContext(operation: String): Class<out RequestContext> =
        schema.operations[operation]?.context ?: report("Unregistered RequestContext operation")

    override fun resolveRequestContextMethod(operation: String): Method =
        schema.operations[operation]?.method ?: report("Unregistered RequestContext operation")

    override fun resolveDomainMethod(operation: String): Method =
        schema.operations[operation]?.domain ?: report("Unregistered Couch operation")

    override fun resolveServiceClass(context: Class<out RequestContext>): Class<*> {
        require(schema.operations.values.any { it.context == context }) { "Unregistered RequestContext" }
        return RelaxFactoryDomain::class.java
    }

    override fun resolveClass(token: String): Class<out BaseProxy> =
        schema.proxies[token] ?: report("Unregistered proxy")

    override fun resolveTypeToken(proxy: Class<out BaseProxy>): String =
        schema.proxies.entries.firstOrNull { it.value == proxy }?.key ?: report("Unregistered proxy")

    override fun resolveDomainClass(client: Class<*>): Class<*> = when {
        schema.proxies.values.any { it == client } -> ConfixBean::class.java
        client == List::class.java || client == Set::class.java || client == Map::class.java -> client
        ValueCodex.canDecode(client) -> client
        else -> report("Unregistered domain class")
    }

    override fun <T : Any?> resolveClientType(domainClass: Class<*>, client: Class<T>, required: Boolean): Class<out T>? {
        val result = when {
            domainClass == ConfixBean::class.java -> schema.proxies.values.firstOrNull { client.isAssignableFrom(it) }
            List::class.java.isAssignableFrom(domainClass) -> List::class.java.takeIf { client.isAssignableFrom(it) }
            Set::class.java.isAssignableFrom(domainClass) -> Set::class.java.takeIf { client.isAssignableFrom(it) }
            Map::class.java.isAssignableFrom(domainClass) -> Map::class.java.takeIf { client.isAssignableFrom(it) }
            ValueCodex.canDecode(domainClass) -> domainClass.takeIf { client.isAssignableFrom(it) }
            else -> null
        }
        if (result == null && required) return report("Unregistered client type")
        return result?.asSubclass(client)
    }

    override fun <T : Any?> createDomainObject(clazz: Class<T>): T {
        if (clazz != ConfixBean::class.java) return report("Unregistered domain class")
        return clazz.cast(domain.create())
    }

    override fun <T : Any?> loadDomainObject(clazz: Class<T>, domainId: Any): T? {
        if (clazz != ConfixBean::class.java || domainId !is String) return report("Unregistered domain class")
        return domain.get(domainId)?.let(clazz::cast)
    }

    override fun getIdType(domainType: Class<*>): Class<*> {
        if (domainType != ConfixBean::class.java) return report("Unregistered domain class")
        return String::class.java
    }

    override fun getId(domainObject: Any): Any? = (domainObject as ConfixBean).let { bean ->
        if (domain.dyninvoke.get(bean, "_rev", String::class.java) == null) null
        else domain.dyninvoke.get(bean, "_id", String::class.java)
    }

    override fun getVersion(domainObject: Any): Any? =
        domain.dyninvoke.get(domainObject as ConfixBean, "_rev", String::class.java)

    override fun isLive(domainObject: Any): Boolean =
        (getId(domainObject) as? String)?.let { domain.store.get(it) != null } ?: false

    override fun getProperty(domainObject: Any, property: String): Any? {
        val bean = domainObject as ConfixBean
        val type = schema.propertyTypes[property] ?: return report("Unregistered proxy property")
        return when (property) {
            "id" -> domain.dyninvoke.get(bean, "_id", type)
            "rev" -> domain.dyninvoke.get(bean, "_rev", type)
            "json" -> jsonOf(bean.fields().filterKeys { it != "_id" && it != "_rev" })
            else -> domain.dyninvoke.get(bean, property, type)
        }
    }

    override fun setProperty(domainObject: Any, property: String, expectedType: Class<*>, value: Any?) {
        val bean = domainObject as ConfixBean
        if (schema.propertyTypes[property] != expectedType) report<Unit>("Unregistered proxy property type")
        when (property) {
            "id" -> {
                val prior = domain.revisions[bean]
                if (prior?.id != null && prior.id != value) report<Unit>("A persistent Couch id is immutable")
                domain.dyninvoke.set(bean, "_id", value)
            }
            "rev" -> {
                if (value != domain.dyninvoke.get(bean, "_rev", String::class.java)) report<Unit>("Couch revisions are assigned by persistence")
            }
            "json" -> {
                val replacement = ConfixBean.from(value as? String ?: "{}").fields().filterKeys { it != "_id" && it != "_rev" }
                val metadata = bean.fields().filterKeys { it == "_id" || it == "_rev" }
                bean.replace(ConfixBean.from(replacement + metadata).document)
            }
            else -> domain.dyninvoke.set(bean, property, value)
        }
    }

    override fun requiresServiceLocator(contextMethod: Method, domainMethod: Method): Boolean = false

    override fun invoke(domainMethod: Method, vararg args: Any?): Any? {
        return try {
            val name = dispatch[domainMethod] ?: return report("Unregistered Couch method")
            domain.dyninvoke.call(name, *args)
        } catch (failure: Exception) {
            report(InvocationTargetException(failure))
        }
    }
}

/** Synchronous processor ownership is composed into HTX by the common bounded RF element. */
class GwtCouchRequestFactory(
    db: Couch,
    val dyninvoke: ConfixDynInvoke = ConfixDynInvoke(),
    val schema: RequestFactorySchema = RequestFactorySchema(),
    viewServer: ViewServer = ViewServer(),
) : AutoCloseable {
    val domain = RelaxFactoryDomain(RelaxStore.of(db), dyninvoke, viewServer)
    val decorator = RelaxFactoryServiceLayer(schema, domain)
    val processor = SimpleRequestProcessor(ServiceLayer.create(decorator))
    var closed = false

    @Synchronized
    fun process(payload: String): String {
        check(!closed) { "RequestFactory is closed" }
        domain.clear()
        try {
            // Decode original RF metadata, without translating or replacing the RF message.
            val message = AutoBeanCodex.decode(MESSAGES, RequestMessage::class.java, payload).`as`()
            for (operation in message.operations.orEmpty()) {
                if (operation.serverId == null) continue
                val id = ValueCodex.decode(String::class.java, com.google.web.bindery.autobean.shared.impl.StringQuoter.split(decode(operation.serverId))) as String
                val rev = operation.version?.let { encoded ->
                    ValueCodex.decode(String::class.java, com.google.web.bindery.autobean.shared.impl.StringQuoter.split(decode(encoded))) as String
                }
                require(!domain.clientRevisions.containsKey(id) || domain.clientRevisions[id] == rev) { "Conflicting RequestFactory revisions" }
                domain.clientRevisions[id] = rev
            }
            return processor.process(payload)
        } finally {
            domain.clear()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        domain.clear()
        dyninvoke.close()
    }

    companion object {
        val MESSAGES: MessageFactory = AutoBeanFactorySource.create(MessageFactory::class.java)
        fun decode(value: String): String = Base64Utils.fromBase64(value).toString(Charsets.UTF_8)
    }
}
