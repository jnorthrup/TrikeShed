package borg.trikeshed.couch.requestfactory

object RequestFactoryJsonCodec {
    fun callToJson(call: RequestFactoryCall): String = buildJsonObject(
        listOf(
            "context" to stringLiteral(call.context),
            "method" to stringLiteral(call.method),
            "arguments" to transportArrayLiteral(call.arguments),
        ),
    )

    fun callFromJson(json: String): RequestFactoryCall {
        val map = parseObject(json)
        return RequestFactoryCall(
            context = map.string("context"),
            method = map.string("method"),
            arguments = map.list("arguments").map(::toTransportValue),
        )
    }

    fun responseToJson(response: RequestFactoryResponse): String = buildJsonObject(
        listOf(
            "success" to response.success.toString(),
            "value" to transportLiteral(response.value),
            "updatedEntities" to arrayLiteral(response.updatedEntities.map(::entityDeltaLiteral)),
            "validationErrors" to arrayLiteral(response.validationErrors.map(::constraintViolationLiteral)),
        ),
    )

    fun responseFromJson(json: String): RequestFactoryResponse {
        val map = parseObject(json)
        return RequestFactoryResponse(
            success = map.boolean("success"),
            value = map["value"]?.let(::toTransportValue),
            updatedEntities = map.list("updatedEntities").map(::toEntityDelta),
            validationErrors = map.list("validationErrors").map(::toConstraintViolation),
        )
    }

    fun schemaToJson(schema: RequestFactorySchema): String = buildJsonObject(
        listOf(
            "factory" to factoryLiteral(schema.factory),
            "contexts" to arrayLiteral(schema.contexts.map(::contextLiteral)),
            "proxies" to arrayLiteral(schema.proxies.map(::proxyLiteral)),
            "validation" to arrayLiteral(schema.validation.map(::validationLiteral)),
        ),
    )

    fun schemaFromJson(json: String): RequestFactorySchema {
        val map = parseObject(json)
        return RequestFactorySchema(
            factory = toFactory(map.objectValue("factory")),
            contexts = map.list("contexts").map { toContext(it as Map<String, Any?>) },
            proxies = map.list("proxies").map { toProxy(it as Map<String, Any?>) },
            validation = map.list("validation").map { toValidation(it as Map<String, Any?>) },
        )
    }

    fun gwtRpcRequestToJson(request: GwtRpcRequest): String = buildJsonObject(
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

    fun gwtRpcRequestFromJson(json: String): GwtRpcRequest {
        val map = parseObject(json)
        return GwtRpcRequest(
            service = map.string("service"),
            method = map.string("method"),
            parameterTypes = map.list("parameterTypes").map { it as String },
            parameters = map.list("parameters").map(::toTransportValue),
            serializationPolicy = map["serializationPolicy"] as String?,
            strongName = map["strongName"] as String?,
            async = map.boolean("async"),
        )
    }

    fun gwtRpcResponseToJson(response: GwtRpcResponse): String = buildJsonObject(
        listOf(
            "returnType" to stringOrNullLiteral(response.returnType),
            "value" to transportLiteral(response.value),
            "violations" to arrayLiteral(response.violations.map(::constraintViolationLiteral)),
            "exceptionType" to stringOrNullLiteral(response.exceptionType),
            "message" to stringOrNullLiteral(response.message),
        ),
    )

    fun gwtRpcResponseFromJson(json: String): GwtRpcResponse {
        val map = parseObject(json)
        return GwtRpcResponse(
            returnType = map["returnType"] as String?,
            value = map["value"]?.let(::toTransportValue),
            violations = map.list("violations").map(::toConstraintViolation),
            exceptionType = map["exceptionType"] as String?,
            message = map["message"] as String?,
        )
    }

    private fun factoryLiteral(factory: RequestFactorySpec): String = buildJsonObject(
        listOf(
            "name" to stringLiteral(factory.name),
            "servletPath" to stringLiteral(factory.servletPath),
            "requestPath" to stringLiteral(factory.requestPath),
            "eventBusType" to stringOrNullLiteral(factory.eventBusType),
        ),
    )

    private fun contextLiteral(context: RequestContextSpec): String = buildJsonObject(
        listOf(
            "name" to stringLiteral(context.name),
            "serviceType" to stringLiteral(context.serviceType),
            "serviceAnnotation" to stringOrNullLiteral(context.serviceAnnotation?.name),
            "methods" to arrayLiteral(context.methods.map(::methodLiteral)),
        ),
    )

    private fun methodLiteral(method: RequestMethodSpec): String = buildJsonObject(
        listOf(
            "name" to stringLiteral(method.name),
            "kind" to stringLiteral(method.kind.name),
            "returnType" to stringLiteral(method.returnType),
            "parameters" to arrayLiteral(method.parameters.map(::parameterLiteral)),
        ),
    )

    private fun parameterLiteral(parameter: ParameterSpec): String = buildJsonObject(
        listOf(
            "name" to stringLiteral(parameter.name),
            "type" to stringLiteral(parameter.type),
            "annotations" to stringArrayLiteral(parameter.annotations),
        ),
    )

    private fun proxyLiteral(proxy: ProxySpec): String = when (proxy) {
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

    private fun propertyLiteral(property: PropertySpec): String = buildJsonObject(
        listOf(
            "name" to stringLiteral(property.name),
            "type" to stringLiteral(property.type),
            "readOnly" to property.readOnly.toString(),
        ),
    )

    private fun validationLiteral(validation: ValidationRuleSpec): String = buildJsonObject(
        listOf(
            "targetType" to stringLiteral(validation.targetType),
            "constraint" to stringLiteral(validation.constraint),
            "message" to stringOrNullLiteral(validation.message),
        ),
    )

    private fun entityDeltaLiteral(delta: EntityDelta): String = buildJsonObject(
        listOf(
            "type" to stringLiteral(delta.type),
            "id" to transportLiteral(delta.id),
            "version" to transportLiteral(delta.version),
            "properties" to objectLiteral(delta.properties.mapValues { transportLiteral(it.value) }),
        ),
    )

    private fun constraintViolationLiteral(violation: ConstraintViolation): String = buildJsonObject(
        listOf(
            "path" to stringLiteral(violation.path),
            "message" to stringLiteral(violation.message),
            "invalidValue" to transportLiteral(violation.invalidValue),
        ),
    )

    private fun transportArrayLiteral(values: List<TransportValue>): String = arrayLiteral(values.map(::transportLiteral))

    private fun stringArrayLiteral(values: List<String>): String = arrayLiteral(values.map(::stringLiteral))

    private fun transportLiteral(value: TransportValue?): String = when (value) {
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

    private fun objectLiteral(values: Map<String, String>): String = buildJsonObject(values.entries.map { it.key to it.value })

    private fun arrayLiteral(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]", separator = ",")

    private fun buildJsonObject(entries: List<Pair<String, String>>): String = entries.joinToString(prefix = "{", postfix = "}", separator = ",") {
        stringLiteral(it.first) + ":" + it.second
    }

    private fun stringOrNullLiteral(value: String?): String = value?.let(::stringLiteral) ?: "null"

    private fun stringLiteral(value: String): String = buildString {
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

    private fun toFactory(map: Map<String, Any?>): RequestFactorySpec = RequestFactorySpec(
        name = map.string("name"),
        servletPath = map.string("servletPath"),
        requestPath = map.string("requestPath"),
        eventBusType = map["eventBusType"] as String?,
    )

    private fun toContext(map: Map<String, Any?>): RequestContextSpec = RequestContextSpec(
        name = map.string("name"),
        serviceType = map.string("serviceType"),
        serviceAnnotation = (map["serviceAnnotation"] as String?)?.let(ServiceAnnotation::valueOf),
        methods = map.list("methods").map { toMethod(it as Map<String, Any?>) },
    )

    private fun toMethod(map: Map<String, Any?>): RequestMethodSpec = RequestMethodSpec(
        name = map.string("name"),
        kind = RequestMethodKind.valueOf(map.string("kind")),
        returnType = map.string("returnType"),
        parameters = map.list("parameters").map { toParameter(it as Map<String, Any?>) },
    )

    private fun toParameter(map: Map<String, Any?>): ParameterSpec = ParameterSpec(
        name = map.string("name"),
        type = map.string("type"),
        annotations = map.listOrEmpty("annotations").map { it as String },
    )

    private fun toProxy(map: Map<String, Any?>): ProxySpec {
        val properties = map.list("properties").map { toProperty(it as Map<String, Any?>) }
        val extraTypes = map.listOrEmpty("extraTypes").map { it as String }
        return if (map.containsKey("idProperty") || map.containsKey("versionProperty")) {
            EntityProxySpec(
                name = map.string("name"),
                serverType = map.string("serverType"),
                idProperty = map["idProperty"] as String?,
                versionProperty = map["versionProperty"] as String?,
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

    private fun toProperty(map: Map<String, Any?>): PropertySpec = PropertySpec(
        name = map.string("name"),
        type = map.string("type"),
        readOnly = map.booleanOrDefault("readOnly", false),
    )

    private fun toValidation(map: Map<String, Any?>): ValidationRuleSpec = ValidationRuleSpec(
        targetType = map.string("targetType"),
        constraint = map.string("constraint"),
        message = map["message"] as String?,
    )

    private fun toEntityDelta(value: Any?): EntityDelta {
        val map = value as Map<String, Any?>
        val properties = (map["properties"] as? Map<String, Any?>).orEmpty().mapValues { toTransportValue(it.value) }
        return EntityDelta(
            type = map.string("type"),
            id = map["id"]?.let(::toTransportValue),
            version = map["version"]?.let(::toTransportValue),
            properties = properties,
        )
    }

    private fun toConstraintViolation(value: Any?): ConstraintViolation {
        val map = value as Map<String, Any?>
        return ConstraintViolation(
            path = map.string("path"),
            message = map.string("message"),
            invalidValue = map["invalidValue"]?.let(::toTransportValue),
        )
    }

    private fun toTransportValue(value: Any?): TransportValue = when (value) {
        null -> TransportValue.NullValue
        is String -> TransportValue.StringValue(value)
        is Boolean -> TransportValue.BooleanValue(value)
        is Int -> TransportValue.IntegerValue(value.toLong())
        is Long -> TransportValue.IntegerValue(value)
        is Double -> TransportValue.NumberValue(value)
        is Float -> TransportValue.NumberValue(value.toDouble())
        is List<*> -> TransportValue.ArrayValue(value.map(::toTransportValue))
        is Map<*, *> -> TransportValue.ObjectValue(value.entries.associate { (k, v) -> k as String to toTransportValue(v) })
        else -> error("Unsupported transport value: $value")
    }

    private fun parseObject(json: String): Map<String, Any?> = MiniJsonParser(json).parseObject()

    private fun Map<String, Any?>.string(key: String): String = this[key] as String
    private fun Map<String, Any?>.boolean(key: String): Boolean = this[key] as Boolean
    private fun Map<String, Any?>.booleanOrDefault(key: String, default: Boolean): Boolean = this[key] as? Boolean ?: default
    private fun Map<String, Any?>.list(key: String): List<Any?> = (this[key] as? List<Any?>) ?: emptyList()
    private fun Map<String, Any?>.listOrEmpty(key: String): List<Any?> = (this[key] as? List<Any?>) ?: emptyList()
    private fun Map<String, Any?>.objectValue(key: String): Map<String, Any?> = this[key] as Map<String, Any?>

    private class MiniJsonParser(private val source: String) {
        private var index: Int = 0

        fun parseObject(): Map<String, Any?> {
            skipWhitespace()
            expect('{')
            val result = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> error("Unexpected token at $index in $source")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                result += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> error("Unexpected array token at $index in $source")
                }
            }
        }

        private fun parseValue(): Any? = when (val token = peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> error("Unexpected value token '$token' at $index in $source")
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (index < source.length) {
                val ch = source[index++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        val escaped = source[index++]
                        out.append(
                            when (escaped) {
                                '"' -> '"'
                                '\\' -> '\\'
                                '/' -> '/'
                                'b' -> '\b'
                                'f' -> '\u000C'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                else -> escaped
                            },
                        )
                    }
                    else -> out.append(ch)
                }
            }
            error("Unterminated string in $source")
        }

        private fun parseNumber(): Number {
            val start = index
            if (source[index] == '-') index++
            while (index < source.length && source[index].isDigit()) index++
            val isDouble = if (index < source.length && source[index] == '.') {
                index++
                while (index < source.length && source[index].isDigit()) index++
                true
            } else {
                false
            }
            val raw = source.substring(start, index)
            return if (isDouble) raw.toDouble() else raw.toLong()
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            require(source.startsWith(literal, index)) { "Expected $literal at $index in $source" }
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        private fun expect(expected: Char) {
            require(peek() == expected) { "Expected '$expected' at $index in $source" }
            index++
        }

        private fun peek(): Char = source.getOrElse(index) { '\u0000' }
    }
}
