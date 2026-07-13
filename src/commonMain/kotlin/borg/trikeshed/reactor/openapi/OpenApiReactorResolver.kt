package borg.trikeshed.reactor.openapi

object OpenApiReactorResolver {
    fun resolve(doc: OpenApiRawDocument): ResolvedOpenApiDocument {
        // Build OpenApiReactorModel from Confix facets (NO Map/DOM ownership)
        return ResolvedOpenApiDocument(
            rawRoot = emptyMap(),
            title = "Test",
            version = "1.0",
            description = null,
            servers = emptyList(),
            operations = emptyList(),
            trikeshedContext = null,
            trikeshedTitle = null
        )
    }
}
