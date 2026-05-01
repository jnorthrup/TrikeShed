package borg.trikeshed.couch.requestfactory

data class RequestFactoryOpenApiDocument(
    val openapi: String,
    val path: String,
    val operationId: String,
    val contentType: String,
)

object RequestFactoryOpenApiYamlCodec {
    fun toYaml(): String = """
        |openapi: 3.1.0
        |info:
        |  title: GWT RequestFactory Transport
        |  version: latest
        |components:
        |  securitySchemes:
        |    CCEK:
        |      type: apiKey
        |      in: header
        |      name: X-CCEK-Key
        |      description: TrikeShed CCEK keyed service header
        |  schemas:
        |    RequestFactoryCall:
        |      type: object
        |paths:
        |  ${RequestFactoryTransportContract.PATH}:
        |    post:
        |      operationId: ${RequestFactoryTransportContract.OPERATION_ID}
        |      summary: RequestFactory invoke endpoint with TrikeShed CCEK bindings
        |      security:
        |        - CCEK: []
        |      parameters:
        |        - name: X-CCEK-Key
        |          in: header
        |          required: false
        |          schema:
        |            type: string
        |          description: "Optional CCEK key used to bind request to coroutine-scoped KeyedService"
        |      requestBody:
        |        required: true
        |        content:
        |          ${RequestFactoryTransportContract.CONTENT_TYPE}:
        |            schema:
        |              ${'$'}ref: '#/components/schemas/RequestFactoryCall'
        |""".trimMargin()

    fun fromYaml(yaml: String): RequestFactoryOpenApiDocument {
        val lines = yaml.lines()
        val openapi = lines.first { it.startsWith("openapi:") }.substringAfter(':').trim()
        val path = lines.first { it.startsWith("  /") }.substringBefore(':').trim()
        val operationId = lines.first { it.trim().startsWith("operationId:") }.substringAfter(':').trim()
        val contentType = lines.first { it.trim().endsWith(":") && it.trim().contains("/") && !it.trim().startsWith("/") }
            .substringBefore(':')
            .trim()
        return RequestFactoryOpenApiDocument(
            openapi = openapi,
            path = path,
            operationId = operationId,
            contentType = contentType,
        )
    }
}
