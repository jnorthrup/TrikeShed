package borg.trikeshed.couch.requestfactory

import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.json.JsonParser

@Suppress("UNCHECKED_CAST")

object RequestFactoryJsonCodec {
    fun callToJson(call: RequestFactoryCall): CharSequence = buildJsonObject(
        listOf(
            "context" to stringLiteral(call.context),
            "method" to stringLiteral(call.method),
            "arguments" to transportArrayLiteral(call.arguments),
            "ccekKey" to stringOrNullLiteral(call.ccekKey),
        ),
    )

    fun callFromJson(json: CharSequence): RequestFactoryCall {
        val map = parseObject(json)
        return RequestFactoryCall(
            context = map.string("context"),
            method = map.string("method"),
            arguments = map.list("arguments").map(::toTransportValue),
            ccekKey = map["ccekKey"] as CharSequence?,
        )
    }

    fun responseToJson(response: RequestFactoryResponse): CharSequence = buildJsonObject(
        listOf(
            "success" to response.success.toString(),
            "value" to transportLiteral(response.value),
            "updatedEntities" to arrayLiteral(response.updatedEntities.map(::entityDeltaLiteral)),
            "validationErrors" to arrayLiteral(response.validationErrors.map(::constraintViolationLiteral)),
        ),
    )

    fun responseFromJson(json: CharSequence): RequestFactoryResponse {
        val map = parseObject(json)
        return RequestFactoryResponse(
            success = map.boolean("success"),
            value = map["value"]?.let(::toTransportValue),
            updatedEntities = map.list("updatedEntities").map(::toEntityDelta),
            validationErrors = map.list("validationErrors").map(::toConstraintViolation),
        )
    }

    fun schemaToJson(schema: RequestFactorySchema): CharSequence = buildJsonObject(
        listOf(
            "factory" to factoryLiteral(schema.factory),
            "contexts" to arrayLiteral(schema.contexts.map(::contextLiteral)),
            "proxies" to arrayLiteral(schema.proxies.map(::proxyLiteral)),
            "validation" to arrayLiteral(schema.validation.map(::validationLiteral)),
        ),
    )

    fun schemaFromJson(json: CharSequence): RequestFactorySchema {
        val map = parseObject(json)
        return RequestFactorySchema(
            factory = toFactory(map.objectValue("factory")),
            contexts = map.list("contexts").map { toContext(it as Map<CharSequence, Any?>) },
            proxies = map.list("proxies").map { toProxy(it as Map<CharSequence, Any?>) },
            validation = map.list("validation").map { toValidation(it as Map<CharSequence, Any?>) },
        )
    }

    fun gwtRpcRequestToJson(request: GwtRpcRequest): CharSequence = buildJsonObject(
        listOf(
            "service" to stringLiteral(request.service),
            "method" to stringLiteral(request.method),
            "parameterTypes" to stringArrayLiteral(request.parameterTypes),
            "parameters" to transportArrayLiteral(request.parameters),
            "serializationPolicy" to stringOrNullLiteral(request.serializationPolicy),
            "strongName" to stringOrNullLiteral(request.strongName),
            "async" to request.async.toString(),
        ),
    )

    fun gwtRpcRequestFromJson(json: CharSequence): GwtRpcRequest {
        val map = parseObject(json)
        return GwtRpcRequest(
            service = map.string("service"),
            method = map.string("method"),
            parameterTypes = map.list("parameterTypes").map { it as CharSequence },
            parameters = map.list("parameters").map(::toTransportValue),
            serializationPolicy = map["serializationPolicy"] as CharSequence?,
            strongName = map["strongName"] as CharSequence?,
            async = map.boolean("async"),
        )
    }

    fun gwtRpcResponseToJson(response: GwtRpcResponse): CharSequence = buildJsonObject(
        listOf(
            "returnType" to stringOrNullLiteral(response.returnType),
            "value" to transportLiteral(response.value),
            "violations" to arrayLiteral(response.violations.map(::constraintViolationLiteral)),
            "exceptionType" to stringOrNullLiteral(response.exceptionType),
            "message" to stringOrNullLiteral(response.message),
        ),
    )

    fun gwtRpcResponseFromJson(json: CharSequence): GwtRpcResponse {
        val map = parseObject(json)
        return GwtRpcResponse(
            returnType = map["returnType"] as CharSequence?,
            value = map["value"]?.let(::toTransportValue),
            violations = map.list("violations").map(::toConstraintViolation),
            exceptionType = map["exceptionType"] as CharSequence?,
            message = map["message"] as CharSequence?,
        )
    }

   fun factoryLiteral(factory: RequestFactorySpec): CharSequence = buildJsonObject(
        listOf(
            "name" to stringLiteral(factory.name),
            "servletPath" to stringLiteral(factory.servletPath),
            "requestPath" to stringLiteral(factory.requestPath),
            "eventBusType" to stringOrNullLiteral(factory.eventBusType),
        ),
    )

   fun contextLiteral(context: RequestContextSpec): CharSequence = buildJsonObject(
        listOf(
            "name" to stringLiteral(context.name),
            "serviceType" to stringLiteral(context.serviceType),
            "serviceAnnotation" to stringOrNullLiteral(context.serviceAnnotation?.name),
            "methods" to arrayLiteral(context.methods.map(::methodLiteral)),
        ),
    )

   fun methodLiteral(method: RequestMethodSpec): CharSequence = buildJsonObject(
        listOf(
            "name" to stringLiteral(method.name),
            "kind" to stringLiteral(method.kind.name),
            "returnType" to stringLiteral(method.returnType),
            "parameters" to arrayLiteral(method.parameters.map(::parameterLiteral)),
        ),
    )

   fun parameterLiteral(parameter: ParameterSpec): CharSequence = buildJsonObject(
        listOf(
            "name" to stringLiteral(parameter.name),
            "type" to stringLiteral(parameter.type),
            "annotations" to stringArrayLiteral(parameter.annotations),
        ),
    )

   fun proxyLiteral(proxy: ProxySpec): CharSequence = when (proxy) {
        is EntityProxySpec -> buildJsonObject(
            listOf(
                "name" to stringLiteral(proxy.name),
                "serverType" to stringLiteral(proxy.serverType),
                "idProperty" to stringOrNullLiteral(proxy.idProperty),
                "versionProperty" to stringOrNullLiteral(proxy.versionProperty),
                "extraTypes" to stringArrayLiteral(proxy.extraTypes),
                "properties" to arrayLiteral(proxy.properties.map(::propertyLiteral)),
            ),
        )
        is ValueProxySpec -> buildJsonObject(
            listOf(
                "name" to stringLiteral(proxy.name),
                "serverType" to stringLiteral(proxy.serverType),
                "extraTypes" to stringArrayLiteral(proxy.extraTypes),
                "properties" to arrayLiteral(proxy.properties.map(::propertyLiteral)),
            ),
        )
    }

   fun propertyLiteral(property: PropertySpec): CharSequence = buildJsonObject(
        listOf(
            "name" to stringLiteral(property.name),
            "type" to stringLiteral(property.type),
            "readOnly" to property.readOnly.toString(),
        ),
    )

   fun validationLiteral(validation: ValidationRuleSpec): CharSequence = buildJsonObject(
        listOf(
            "targetType" to stringLiteral(validation.targetType),
            "constraint" to stringLiteral(validation.constraint),
            "message" to stringOrNullLiteral(validation.message),
        ),
    )

   fun entityDeltaLiteral(delta: EntityDelta): CharSequence = buildJsonObject(
        listOf(
            "type" to stringLiteral(delta.type),
            "id" to transportLiteral(delta.id),
            "version" to transportLiteral(delta.version),
            "properties" to objectLiteral(delta.properties.mapValues { transportLiteral(it.value) }),
        ),
    )

   fun constraintViolationLiteral(violation: ConstraintViolation): CharSequence = buildJsonObject(
        listOf(
            "path" to stringLiteral(violation.path),
            "message" to stringLiteral(violation.message),
            "invalidValue" to transportLiteral(violation.invalidValue),
        ),
    )

   fun transportArrayLiteral(values: List<TransportValue>): CharSequence = arrayLiteral(values.map(::transportLiteral))

   fun stringArrayLiteral(values: List<CharSequence>): CharSequence = arrayLiteral(values.map(::stringLiteral))

   fun transportLiteral(value: TransportValue?): CharSequence = when (value) {
        null,
        TransportValue.NullValue,
        -> "null"
        is TransportValue.StringValue -> stringLiteral(value.value)
        is TransportValue.BooleanValue -> value.value.toString()
        is TransportValue.IntegerValue -> value.value.toString()
        is TransportValue.NumberValue -> value.value.toString()
        is TransportValue.ArrayValue -> arrayLiteral(value.values.map(::transportLiteral))
        is TransportValue.ObjectValue -> objectLiteral(value.values.mapValues { transportLiteral(it.value) })
    }

   fun objectLiteral(values: Map<CharSequence, CharSequence>): CharSequence = buildJsonObject(values.entries.map { it.key to it.value })

   fun arrayLiteral(values: List<CharSequence>): CharSequence = values.joinToString(prefix = "[", postfix = "]", separator = ",")

   fun buildJsonObject(entries: List<Pair<CharSequence, CharSequence>>): CharSequence = entries.joinToString(prefix = "{", postfix = "}", separator = ",") {
        stringLiteral(it.first) + ":" + it.second
    }

   fun stringOrNullLiteral(value: CharSequence?): CharSequence = value?.let(::stringLiteral) ?: "null"

   fun stringLiteral(value: CharSequence): CharSequence = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

   fun toFactory(map: Map<CharSequence, Any?>): RequestFactorySpec = RequestFactorySpec(
        name = map.string("name"),
        servletPath = map.string("servletPath"),
        requestPath = map.string("requestPath"),
        eventBusType = map["eventBusType"] as CharSequence?,
    )

   fun toContext(map: Map<CharSequence, Any?>): RequestContextSpec = RequestContextSpec(
        name = map.string("name"),
        serviceType = map.string("serviceType"),
        serviceAnnotation = (map["serviceAnnotation"] as CharSequence?)?.let(ServiceAnnotation::valueOf),
        methods = map.list("methods").map { toMethod(it as Map<CharSequence, Any?>) },
    )

   fun toMethod(map: Map<CharSequence, Any?>): RequestMethodSpec = RequestMethodSpec(
        name = map.string("name"),
        kind = RequestMethodKind.valueOf(map.string("kind")),
        returnType = map.string("returnType"),
        parameters = map.list("parameters").map { toParameter(it as Map<CharSequence, Any?>) },
    )

   fun toParameter(map: Map<CharSequence, Any?>): ParameterSpec = ParameterSpec(
        name = map.string("name"),
        type = map.string("type"),
        annotations = map.listOrEmpty("annotations").map { it as CharSequence },
    )

   fun toProxy(map: Map<CharSequence, Any?>): ProxySpec {
        val properties = map.list("properties").map { toProperty(it as Map<CharSequence, Any?>) }
        val extraTypes = map.listOrEmpty("extraTypes").map { it as CharSequence }
        return if (map.containsKey("idProperty") || map.containsKey("versionProperty")) {
            EntityProxySpec(
                name = map.string("name"),
                serverType = map.string("serverType"),
                idProperty = map["idProperty"] as CharSequence?,
                versionProperty = map["versionProperty"] as CharSequence?,
                extraTypes = extraTypes,
                properties = properties,
            )
        } else {
            ValueProxySpec(
                name = map.string("name"),
                serverType = map.string("serverType"),
                extraTypes = extraTypes,
                properties = properties,
            )
        }
    }

   fun toProperty(map: Map<CharSequence, Any?>): PropertySpec = PropertySpec(
        name = map.string("name"),
        type = map.string("type"),
        readOnly = map.booleanOrDefault("readOnly", false),
    )

   fun toValidation(map: Map<CharSequence, Any?>): ValidationRuleSpec = ValidationRuleSpec(
        targetType = map.string("targetType"),
        constraint = map.string("constraint"),
        message = map["message"] as CharSequence?,
    )

   fun toEntityDelta(value: Any?): EntityDelta {
        val map = value as Map<CharSequence, Any?>
        val properties = (map["properties"] as? Map<CharSequence, Any?>).orEmpty().mapValues { toTransportValue(it.value) }
        return EntityDelta(
            type = map.string("type"),
            id = map["id"]?.let(::toTransportValue),
            version = map["version"]?.let(::toTransportValue),
            properties = properties,
        )
    }

   fun toConstraintViolation(value: Any?): ConstraintViolation {
        val map = value as Map<CharSequence, Any?>
        return ConstraintViolation(
            path = map.string("path"),
            message = map.string("message"),
            invalidValue = map["invalidValue"]?.let(::toTransportValue),
        )
    }

   fun toTransportValue(value: Any?): TransportValue = when (value) {
        null -> TransportValue.NullValue
        is CharSequence -> TransportValue.StringValue(value)
        is Boolean -> TransportValue.BooleanValue(value)
        is Int -> TransportValue.IntegerValue(value.toLong())
        is Long -> TransportValue.IntegerValue(value)
        is Double -> TransportValue.NumberValue(value)
        is Float -> TransportValue.NumberValue(value.toDouble())
        is List<*> -> TransportValue.ArrayValue(value.map(::toTransportValue))
        is Map<*, *> -> TransportValue.ObjectValue(value.entries.associate { (k, v) -> k as CharSequence to toTransportValue(v) })
        else -> error("Unsupported transport value: $value")
    }

   fun parseObject(json: CharSequence): Map<CharSequence, Any?> =
        JsonParser.reify(json.toSeries()) as? Map<CharSequence, Any?>
            ?: error("Expected JSON object: $json")

   fun Map<CharSequence, Any?>.string(key: CharSequence): CharSequence = this[key] as CharSequence
   fun Map<CharSequence, Any?>.boolean(key: CharSequence): Boolean = this[key] as Boolean
   fun Map<CharSequence, Any?>.booleanOrDefault(key: CharSequence, default: Boolean): Boolean = this[key] as? Boolean ?: default
   fun Map<CharSequence, Any?>.list(key: CharSequence): List<Any?> = (this[key] as? List<Any?>) ?: emptyList()
   fun Map<CharSequence, Any?>.listOrEmpty(key: CharSequence): List<Any?> = (this[key] as? List<Any?>) ?: emptyList()
   fun Map<CharSequence, Any?>.objectValue(key: CharSequence): Map<CharSequence, Any?> = this[key] as Map<CharSequence, Any?>
}
