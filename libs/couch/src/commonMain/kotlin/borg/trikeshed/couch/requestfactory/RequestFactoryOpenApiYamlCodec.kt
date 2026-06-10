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
        |paths:
        |  ${RequestFactoryTransportContract.PATH}:
        |    post:
        |      operationId: ${RequestFactoryTransportContract.OPERATION_ID}
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
