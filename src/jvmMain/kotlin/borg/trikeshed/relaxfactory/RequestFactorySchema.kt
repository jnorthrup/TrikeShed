package borg.trikeshed.relaxfactory

import com.google.web.bindery.requestfactory.shared.*
import com.google.web.bindery.requestfactory.vm.impl.OperationKey
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/** The shipped Java interface metadata is sufficient for the server; no GWT compiler or APT. */
interface CouchGwtFactory : RequestFactory {
    fun couch(): CouchGwtContext
}

@Service(RelaxFactoryDomain::class)
interface CouchGwtContext : RequestContext {
    fun get(id: String): Request<CouchDocumentProxy>
    fun persist(document: CouchDocumentProxy): Request<CouchDocumentProxy>
    fun delete(document: CouchDocumentProxy): Request<Boolean>
    fun storedView(design: String, view: String, query: String): Request<String>
    fun call(operation: String, document: CouchDocumentProxy, arguments: String): Request<String>
}

@ProxyFor(ConfixBean::class)
interface CouchDocumentProxy : EntityProxy {
    fun getId(): String?
    fun setId(value: String?)
    fun getRev(): String?
    fun setRev(value: String?)
    fun getJson(): String?
    fun setJson(value: String?)
}

typealias RequestFactoryToken = String
typealias RequestOperationToken = String
typealias RequestProxyToken = String

class RequestFactoryOperation(val context: Class<out RequestContext>, val method: Method, val domain: Method)

/** Only explicitly supplied factories, their contexts, and their Confix proxies are admitted. */
class RequestFactorySchema(vararg registrations: Class<out RequestFactory>) {
    val factories: Map<RequestFactoryToken, Class<out RequestFactory>>
    val operations: Map<RequestOperationToken, RequestFactoryOperation>
    val proxies: Map<RequestProxyToken, Class<out BaseProxy>>
    val properties: Map<Class<out BaseProxy>, Map<String, Type>>

    init {
        val factoryMap = linkedMapOf<RequestFactoryToken, Class<out RequestFactory>>()
        val operationMap = linkedMapOf<RequestOperationToken, RequestFactoryOperation>()
        val proxyMap = linkedMapOf<RequestProxyToken, Class<out BaseProxy>>()
        val fields = linkedMapOf<Class<out BaseProxy>, Map<String, Type>>()
        fun proxy(type: Type) {
            if (type is WildcardType) { type.upperBounds.forEach(::proxy); return }
            if (type is ParameterizedType) {
                type.actualTypeArguments.forEach(::proxy)
                return
            }
            if (type !is Class<*> || !BaseProxy::class.java.isAssignableFrom(type)) return
            require(type.isInterface && (EntityProxy::class.java.isAssignableFrom(type) || ValueProxy::class.java.isAssignableFrom(type))) { "A Couch proxy must be an EntityProxy or ValueProxy interface" }
            require(type.getAnnotation(ProxyFor::class.java)?.value == ConfixBean::class) { "A Couch proxy must map to ConfixBean" }
            val clazz = type.asSubclass(BaseProxy::class.java)
            if (proxyMap.putIfAbsent(proxyToken(clazz), clazz) != null) return
            val declared = linkedMapOf<String, Type>()
            fields[clazz] = declared
            for (method in clazz.methods) {
                val name = property(method) ?: continue
                val valueType = if (method.parameterCount == 0) method.genericReturnType else method.genericParameterTypes.single()
                val prior = declared.putIfAbsent(name, valueType)
                require(prior == null || rawType(prior) == rawType(valueType)) { "Conflicting Couch property types for $name" }
                if (method.parameterCount == 0) declared[name] = valueType
                method.genericParameterTypes.forEach(::proxy)
                proxy(method.genericReturnType)
            }
        }
        for (factory in registrations.ifEmpty { arrayOf(CouchGwtFactory::class.java) }) {
            require(factory.isInterface) { "A RequestFactory must be an interface" }
            require(factoryMap.putIfAbsent(factory.name, factory) == null) { "Duplicate RequestFactory" }
            for (endpoint in factory.methods) {
                if (!RequestContext::class.java.isAssignableFrom(endpoint.returnType)) continue
                val context = endpoint.returnType.asSubclass(RequestContext::class.java)
                require(context.getAnnotation(Service::class.java)?.value == RelaxFactoryDomain::class) { "A Couch context must use RelaxFactoryDomain" }
                for (method in context.methods) {
                    if (!Request::class.java.isAssignableFrom(method.returnType)) continue
                    method.genericParameterTypes.forEach(::proxy)
                    proxy(method.genericReturnType)
                    val parameters = method.parameterTypes.map { if (BaseProxy::class.java.isAssignableFrom(it)) ConfixBean::class.java else it }.toTypedArray()
                    // A registered context method is also valid dyninvoke metadata; it is never reflectively executed.
                    val domain = try { RelaxFactoryDomain::class.java.getMethod(method.name, *parameters) } catch (_: NoSuchMethodException) { method }
                    operationMap[operationToken(context, method)] = RequestFactoryOperation(context, method, domain)
                }
            }
        }
        factories = factoryMap
        operations = operationMap
        proxies = proxyMap
        properties = fields
    }

    companion object {
        fun proxyToken(proxy: Class<out BaseProxy>): RequestProxyToken = OperationKey.hash(proxy.name)

        fun operationToken(context: Class<out RequestContext>, method: Method): RequestOperationToken =
            OperationKey(context.name, method.name, method.parameterTypes.joinToString(prefix = "(", postfix = ")V", separator = "", transform = ::descriptor)).toString()

        fun descriptor(type: Class<*>): String = when (type) {
            java.lang.Boolean.TYPE -> "Z"
            java.lang.Byte.TYPE -> "B"
            java.lang.Character.TYPE -> "C"
            java.lang.Double.TYPE -> "D"
            java.lang.Float.TYPE -> "F"
            java.lang.Integer.TYPE -> "I"
            java.lang.Long.TYPE -> "J"
            java.lang.Short.TYPE -> "S"
            java.lang.Void.TYPE -> "V"
            else -> "L${type.name.replace('.', '/')};"
        }

        fun rawType(type: Type): Class<*> = when (type) {
            is Class<*> -> type
            is ParameterizedType -> rawType(type.rawType)
            is WildcardType -> rawType(type.upperBounds.single())
            else -> throw IllegalArgumentException("Unsupported RequestFactory property type")
        }

        fun property(method: Method): String? {
            val prefix = when {
                method.name.startsWith("get") && method.parameterCount == 0 && method.returnType != java.lang.Void.TYPE -> "get"
                method.name.startsWith("is") && method.parameterCount == 0 && method.returnType in setOf(java.lang.Boolean.TYPE, java.lang.Boolean::class.java) -> "is"
                method.name.startsWith("set") && method.parameterCount == 1 && method.returnType == java.lang.Void.TYPE -> "set"
                else -> return null
            }
            val name = method.name.removePrefix(prefix)
            return name.takeIf { it.isNotEmpty() }?.replaceFirstChar(Char::lowercaseChar)
        }
    }
}
