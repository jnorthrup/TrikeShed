package borg.trikeshed.couch.requestfactory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequestFactoryJsonCodecRedTest {
    @Test
    fun roundTripsRequestFactoryCallsResponsesAndTransportValuesAsJson() {
        val call = RequestFactoryCall(
            context = "EmployeeRequest",
            method = "findEmployee",
            arguments = listOf(
                TransportValue.IntegerValue(7),
                TransportValue.ObjectValue(
                    mapOf(
                        "includeDocs" to TransportValue.BooleanValue(true),
                        "tags" to TransportValue.ArrayValue(
                            listOf(
                                TransportValue.StringValue("vw"),
                                TransportValue.StringValue("audi"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val callJson = RequestFactoryJsonCodec.callToJson(call)
        val decodedCall = RequestFactoryJsonCodec.callFromJson(callJson)

        assertEquals(call, decodedCall)
        assertTrue(callJson.contains("\"context\":\"EmployeeRequest\""))
        assertTrue(callJson.contains("\"method\":\"findEmployee\""))

        val response = RequestFactoryResponse(
            success = true,
            value = TransportValue.ObjectValue(
                mapOf(
                    "id" to TransportValue.IntegerValue(7),
                    "displayName" to TransportValue.StringValue("Ada"),
                ),
            ),
            updatedEntities = listOf(
                EntityDelta(
                    type = "EmployeeProxy",
                    id = TransportValue.IntegerValue(7),
                    version = TransportValue.IntegerValue(3),
                    properties = mapOf(
                        "displayName" to TransportValue.StringValue("Ada"),
                    ),
                ),
            ),
        )

        val responseJson = RequestFactoryJsonCodec.responseToJson(response)
        val decodedResponse = RequestFactoryJsonCodec.responseFromJson(responseJson)

        assertEquals(response, decodedResponse)
        assertTrue(responseJson.contains("\"success\":true"))
        assertTrue(responseJson.contains("\"updatedEntities\""))
    }

    @Test
    fun roundTripsRequestFactorySchemaJsonIncludingEntityAndValueProxies() {
        val schema = RequestFactorySchema(
            factory = RequestFactorySpec(name = "ExpensesRequestFactory", eventBusType = "SimpleEventBus"),
            contexts = listOf(
                RequestContextSpec(
                    name = "EmployeeRequest",
                    serviceType = "Employee",
                    serviceAnnotation = ServiceAnnotation.Service,
                    methods = listOf(
                        RequestMethodSpec(
                            name = "findEmployee",
                            kind = RequestMethodKind.Request,
                            returnType = "EmployeeProxy",
                            parameters = listOf(ParameterSpec(name = "id", type = "Long", annotations = listOf("Key"))),
                        ),
                    ),
                ),
            ),
            proxies = listOf(
                EntityProxySpec(
                    name = "EmployeeProxy",
                    serverType = "Employee",
                    idProperty = "id",
                    versionProperty = "version",
                    properties = listOf(
                        PropertySpec(name = "id", type = "Long", readOnly = true),
                        PropertySpec(name = "displayName", type = "String"),
                    ),
                ),
                ValueProxySpec(
                    name = "AddressProxy",
                    serverType = "Address",
                    properties = listOf(
                        PropertySpec(name = "street1", type = "String"),
                        PropertySpec(name = "zip", type = "String"),
                    ),
                ),
            ),
            validation = listOf(
                ValidationRuleSpec(targetType = "Employee", constraint = "@NotNull", message = "Name required"),
            ),
        )

        val json = RequestFactoryJsonCodec.schemaToJson(schema)
        val decoded = RequestFactoryJsonCodec.schemaFromJson(json)

        assertEquals(schema, decoded)
        assertTrue(json.contains("\"ExpensesRequestFactory\""))
        assertTrue(json.contains("\"EmployeeProxy\""))
        assertTrue(json.contains("\"AddressProxy\""))
        assertIs<EntityProxySpec>(decoded.proxies[0])
        assertIs<ValueProxySpec>(decoded.proxies[1])
    }

    @Test
    fun roundTripsGwtRpcMessagesAndRequestFactoryOpenApiYaml() {
        val request = GwtRpcRequest(
            service = "com.acme.EmployeeService",
            method = "findEmployee",
            parameterTypes = listOf("java.lang.Long"),
            parameters = listOf(TransportValue.IntegerValue(7)),
            serializationPolicy = "ABC123",
            strongName = "workerStrongName",
        )
        val requestJson = RequestFactoryJsonCodec.gwtRpcRequestToJson(request)
        val decodedRequest = RequestFactoryJsonCodec.gwtRpcRequestFromJson(requestJson)
        assertEquals(request, decodedRequest)

        val response = GwtRpcResponse(
            returnType = "com.acme.EmployeeProxy",
            value = TransportValue.ObjectValue(mapOf("id" to TransportValue.IntegerValue(7))),
            violations = listOf(ConstraintViolation(path = "name", message = "required")),
        )
        val responseJson = RequestFactoryJsonCodec.gwtRpcResponseToJson(response)
        val decodedResponse = RequestFactoryJsonCodec.gwtRpcResponseFromJson(responseJson)
        assertEquals(response, decodedResponse)

        val yaml = RequestFactoryOpenApiYamlCodec.toYaml()
        val document = RequestFactoryOpenApiYamlCodec.fromYaml(yaml)

        assertEquals(RequestFactoryTransportContract.PATH, document.path)
        assertEquals(RequestFactoryTransportContract.OPERATION_ID, document.operationId)
        assertEquals(RequestFactoryTransportContract.CONTENT_TYPE, document.contentType)
        assertTrue(yaml.contains("openapi: 3.1.0"))
        assertTrue(yaml.contains("/gwtRequest:"))
    }
}
