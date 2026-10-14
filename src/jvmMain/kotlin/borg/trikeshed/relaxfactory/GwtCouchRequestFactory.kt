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
import com.google.web.bindery.requestfactory.server.DefaultExceptionHandler
import com.google.web.bindery.requestfactory.shared.*
import com.google.web.bindery.requestfactory.shared.messages.MessageFactory
import com.google.web.bindery.requestfactory.shared.messages.RequestMessage
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import javax.validation.ConstraintViolation

/** The only RF domain access is registered metadata over Confix, decorated with Couch dynamics. */
class RelaxFactoryServiceLayer(
    val schema: RequestFactorySchema,
    val domain: RelaxFactoryDomain,
) : ServiceLayerDecorator() {
    val dispatch = linkedMapOf<Method, String>()
    // RF's default resolver cache is process-global, so only pure generic metadata delegates to it.
    val core: ServiceLayer = ServiceLayer.create()
    var protocolFailure: Exception? = null

    init {
        for ((token, binding) in schema.operations) {
            if (dispatch.containsKey(binding.domain)) continue
            if (binding.domain.declaringClass != RelaxFactoryDomain::class.java) {
                dispatch[binding.domain] = token
                continue
            }
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

    override fun resolveRequestFactory(token: String): Class<out RequestFactory> {
        protocolFailure?.let { return report(InvocationTargetException(it)) }
        return schema.factories[token] ?: report("Unregistered RequestFactory")
    }

    override fun getRequestReturnType(contextMethod: Method): Type = core.getRequestReturnType(contextMethod)

    override fun <T : Any?> validate(domainObject: T): Set<ConstraintViolation<T>> = core.validate(domainObject)

    override fun loadDomainObjects(classes: List<Class<*>>, ids: List<Any>): List<Any?> =
        classes.indices.map { loadDomainObject(classes[it], ids[it]) }

    override fun resolveRequestContext(operation: String): Class<out RequestContext> =
        schema.operations[operation]?.context ?: report("Unregistered RequestContext operation")

    override fun resolveRequestContextMethod(operation: String): Method =
        schema.operations[operation]?.method ?: report("Unregistered RequestContext operation")

    override fun resolveDomainMethod(operation: String): Method {
        domain.invocation = schema.operations[operation]
        return domain.invocation?.domain ?: report("Unregistered Couch operation")
    }

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
        if (ValueProxy::class.java.isAssignableFrom(domain.types[bean]) || domain.dyninvoke.get(bean, "_rev", String::class.java) == null) null
        else domain.dyninvoke.get(bean, "_id", String::class.java)
    }

    override fun getVersion(domainObject: Any): Any? = (domainObject as ConfixBean).let {
        if (ValueProxy::class.java.isAssignableFrom(domain.types[it])) null
        else domain.dyninvoke.get(it, "_rev", String::class.java)
    }

    override fun isLive(domainObject: Any): Boolean =
        (getId(domainObject) as? String)?.let { domain.store.get(it) != null } ?: false

    override fun getProperty(domainObject: Any, property: String): Any? {
        val bean = domainObject as ConfixBean
        val declared = schema.properties[domain.types[bean]]?.get(property) ?: return report("Unregistered proxy property")
        val type = RequestFactorySchema.rawType(declared)
        val entity = EntityProxy::class.java.isAssignableFrom(domain.types[bean])
        return when (property) {
            "id" -> domain.dyninvoke.get(bean, if (entity) "_id" else property, type)
            "rev" -> domain.dyninvoke.get(bean, if (entity) "_rev" else property, type)
            "json" -> if (type == String::class.java && entity) jsonOf(domain.fields(bean).filterKeys { it != "_id" && it != "_rev" }) else domain.property(bean, property, declared)
            else -> if (BaseProxy::class.java.isAssignableFrom(type) || declared is ParameterizedType) domain.property(bean, property, declared) else domain.dyninvoke.get(bean, property, type)
        }
    }

    override fun setProperty(domainObject: Any, property: String, expectedType: Class<*>, value: Any?) {
        val bean = domainObject as ConfixBean
        val declared = schema.properties[domain.types[bean]]?.get(property) ?: return report("Unregistered proxy property")
        val type = RequestFactorySchema.rawType(declared)
        if (resolveDomainClass(type) != expectedType) report<Unit>("Unregistered proxy property type")
        val entity = EntityProxy::class.java.isAssignableFrom(domain.types[bean])
        if (!entity && property in setOf("id", "rev")) {
            domain.dyninvoke.set(bean, property, value)
            return
        }
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
                if (type != String::class.java || !EntityProxy::class.java.isAssignableFrom(domain.types[bean])) {
                    domain.property(bean, property, value, declared)
                    return
                }
                val replacement = ConfixBean.from(value as? String ?: "{}").fields().filterKeys { it != "_id" && it != "_rev" }
                val metadata = bean.fields().filterKeys { it == "_id" || it == "_rev" }
                bean.replace(ConfixBean.from(replacement + metadata).document)
            }
            else -> if (BaseProxy::class.java.isAssignableFrom(type) || declared is ParameterizedType) domain.property(bean, property, value, declared) else domain.dyninvoke.set(bean, property, value)
        }
    }

    override fun requiresServiceLocator(contextMethod: Method, domainMethod: Method): Boolean = false

    override fun invoke(domainMethod: Method, vararg args: Any?): Any? {
        return try {
            val invocation = domain.invocation ?: return report("Unregistered Couch invocation")
            if (invocation.domain != domainMethod) return report("Unregistered Couch invocation")
            domain.returnType = getRequestReturnType(invocation.method)
            val name = dispatch[domainMethod] ?: return report("Unregistered Couch method")
            args.forEach(domain::materialize)
            val result = domain.dyninvoke.call(name, *args)
            args.forEach(domain::refresh)
            domain.convert(result, domain.returnType!!)
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
    val exceptions = DefaultExceptionHandler()
    val processor = SimpleRequestProcessor(decorator).also { it.setExceptionHandler(exceptions) }
    var closed = false

    @Synchronized
    fun process(payload: String): String {
        check(!closed) { "RequestFactory is closed" }
        domain.clear()
        decorator.protocolFailure = null
        try {
            // Decode original RF metadata, without translating or replacing the RF message.
            try {
                val message = AutoBeanCodex.decode(MESSAGES, RequestMessage::class.java, payload).`as`()
                val ids = linkedSetOf<String>()
                for (operation in message.operations.orEmpty()) {
                    val proxy = schema.proxies[operation.typeToken] ?: continue
                    val key = "${operation.typeToken}:${operation.serverId}:${operation.clientId}:${operation.syntheticId}"
                    if (!ids.add(key)) continue
                    if (operation.serverId == null) {
                        domain.creations.addLast(proxy)
                        continue
                    }
                    val id = string(operation.serverId)
                    domain.loads.getOrPut(id) { ArrayDeque() }.addLast(proxy)
                    val rev = operation.version?.let(::string)
                    require(!domain.clientRevisions.containsKey(id) || domain.clientRevisions[id] == rev) { "Conflicting RequestFactory revisions" }
                    domain.clientRevisions[id] = rev
                }
            } catch (failure: Exception) {
                decorator.protocolFailure = failure
            }
            return try { processor.process(payload) } catch (failure: Exception) {
                // Upstream decodes before its error handler; use its own factory/handler for decode failures too.
                val error = exceptions.createServerFailure(failure)
                val failureBean = MESSAGES.failure()
                failureBean.`as`().setMessage(error.message)
                failureBean.`as`().setExceptionType(error.exceptionType)
                failureBean.`as`().setStackTrace(error.stackTraceString)
                failureBean.`as`().setFatal(error.isFatal)
                val response = MESSAGES.response()
                response.`as`().setGeneralFailure(failureBean.`as`())
                AutoBeanCodex.encode(response).payload
            }
        } finally {
            domain.clear()
            decorator.protocolFailure = null
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
        fun decode(value: String): String {
            val padding = value.takeLastWhile { it == '=' }.length
            require(value.length % 4 == 0 && padding <= 2 && value.dropLast(padding).all {
                it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '$' || it == '_'
            }) { "Invalid RequestFactory Base64 metadata" }
            val bytes = Base64Utils.fromBase64(value)
            require(Base64Utils.toBase64(bytes) == value) { "Invalid RequestFactory Base64 metadata" }
            return bytes.toString(Charsets.UTF_8)
        }
        fun string(value: String): String = ValueCodex.decode(String::class.java, com.google.web.bindery.autobean.shared.impl.StringQuoter.split(decode(value))) as String
    }
}
