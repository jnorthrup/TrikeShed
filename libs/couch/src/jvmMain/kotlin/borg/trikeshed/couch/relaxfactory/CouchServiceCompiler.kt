package borg.trikeshed.couch.relaxfactory

import borg.trikeshed.couch.api.CouchDb11DesignDocument
import borg.trikeshed.couch.api.CouchViewDefinition
import java.lang.reflect.Method
import java.net.URLEncoder
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty1
import kotlin.reflect.full.*
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.jvmErasure

inline fun <reified T : CouchService<*>> compile(namespace: CharSequence): CouchViewManifest =
    CouchServiceCompiler.compile(T::class, namespace)

object CouchServiceCompiler {
    fun <T : CouchService<*>> compile(serviceClass: KClass<T>, namespace: CharSequence): CouchViewManifest {
        val simpleName = serviceClass.simpleName!!
        // Strip ViewService or Service suffix, then lowercase
        val entityName = simpleName
            .removeSuffix("ViewService")
            .removeSuffix("Service")
            .replaceFirstChar { it.lowercase() }
        val dbName = namespace + entityName
        val designDocId = "_design/${serviceClass.qualifiedName}"
        val language = "javascript"

        val viewDefs = LongLongSeries.build { putAll(mapOf<CharSequence, CouchViewDefinition>()) }
        val invocations = LongLongSeries.build { putAll(mapOf<CharSequence, CouchViewInvocation>()) })

        for (func in serviceClass.declaredMemberFunctions) {
            func.isAccessible = true
            val viewAnn = func.findAnnotation<View>() ?: continue
            val reduceVal = viewAnn.reduce.takeIf { it.isNotEmpty() }
            viewDefs[func.name] = CouchViewDefinition(map = viewAnn.map, reduce = reduceVal)

            val returnShape = detectReturnShape(func)
            val template = buildTemplate(func, designDocId)
            invocations[func.name] = CouchViewInvocation(
                path = "",
                template = template,
                returnShape = returnShape,
                encodeValue = { arg -> jsonEncode(arg) },
                databaseName = dbName,
            )
        }

        return CouchViewManifest(
            databaseName = dbName,
            designDocument = CouchDb11DesignDocument(id = designDocId, language = language, views = viewDefs),
            views = invocations,
        )
    }

   fun detectReturnShape(func: KFunction<*>): CouchViewInvocation.ReturnShape {
        val retType = func.returnType.jvmErasure
        return when {
            retType == Map::class -> CouchViewInvocation.ReturnShape.MapKeyValue
            retType == Int::class || retType == Long::class || retType == Boolean::class ->
                CouchViewInvocation.ReturnShape.ScalarValue
            else -> CouchViewInvocation.ReturnShape.ListValue
        }
    }

   fun buildTemplate(func: KFunction<*>, designDocId: CharSequence): CharSequence {
        val params = func.valueParameters
        val methodAnnotations = func.annotations

        val parts = SeriesBuffer<CharSequence>()

        // Method-level annotations
        for (ann in methodAnnotations) {
            when (ann) {
                is Descending -> parts += "descending=${ann.value}"
                is Group -> parts += "group=${ann.value}"
                is GroupLevel -> parts += "group_level=${ann.value}"
            }
        }

        // Parameter annotations with %N$s placeholders
        for ((idx, param) in params.withIndex()) {
            val placeholder = "%${idx + 1}\$s"
            for (ann in param.annotations) {
                when (ann) {
                    is Key -> parts += "key=$placeholder"
                    is StartKey -> parts += "startkey=$placeholder"
                    is EndKey -> parts += "endkey=$placeholder"
                    is Limit -> parts += "limit=$placeholder"
                    is Skip -> parts += "skip=$placeholder"
                    is StartKeyDocId -> parts += "startkey_docid=$placeholder"
                    is EndKeyDocId -> parts += "endkey_docid=$placeholder"
                    is Keys -> parts += "keys=$placeholder"
                }
            }
        }

        // Sort alphabetically by key name; CouchDB convention: key/keys go last
        parts.sortBy { part ->
            val key = part.substringBefore("=")
            when (key) {
                "key", "keys" -> "zzz$key"
                else -> key
            }
        }

        val queryStr = if (parts.isNotEmpty()) "?" + parts.joinToString("&") else ""
        return "$designDocId/_view/${func.name}$queryStr"
    }

    internal fun jsonEncode(value: Any?): CharSequence = when (value) {
        null -> "null"
        is CharSequence -> "\"$value\""
        is Number -> value.toString()
        is Boolean -> value.toString()
        is Map<*, *> -> "{" + value.entries.joinToString(",") { (k, v) ->
            "\"$k\":${jsonEncode(v)}"
        } + "}"
        is List<*> -> "[" + value.joinToString(",") { jsonEncode(it) } + "]"
        else -> {
            // Use primary constructor parameters for declaration order (data classes)
            val ctor = value::class.primaryConstructor
            val props = value::class.memberProperties.associateBy { it.name }
            val entries: List<Pair<CharSequence, Any?>> = if (ctor != null) {
                ctor.parameters.mapNotNull { param ->
                    val prop = props[param.name] as? KProperty1<Any?, Any?>
                    prop?.let { p ->
                        p.isAccessible = true
                        param.name!! to p.get(value)
                    }
                }
            } else {
                props.entries.map { (name, prop) ->
                    (prop as KProperty1<Any?, Any?>).isAccessible = true
                    name to prop.get(value)
                }
            }
            "{" + entries.joinToString(",") { (name, v) ->
                "\"$name\":${jsonEncode(v)}"
            } + "}"
        }
    }
}
