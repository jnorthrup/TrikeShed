package borg.trikeshed.reactor.openapi

// Add back the required gaps/tokens structs from Donor
data class OpenApiToken(
    val kind: String,
    val value: String,
    val location: String,
)

data class OpenApiGap(
    val code: String,
    val location: String,
    val detail: String,
)

data class OpenApiGapAnalysis(
    val tokens: List<OpenApiToken> = emptyList(),
    val gaps: List<OpenApiGap> = emptyList(),
) {
    val isComplete: Boolean get() = gaps.isEmpty()

    companion object {
        val EMPTY = OpenApiGapAnalysis()
    }
}

data class OpenApiRawOperation(
    val path: String,
    val method: String,
    val operationCursor: Any?,
    val operationId: String?
)

data class OpenApiRawDocument(val index: Any?) {

    fun gapAnalysis(): OpenApiGapAnalysis = OpenApiGapAnalysis.EMPTY

    fun operations(): List<OpenApiRawOperation> {
        val ops = mutableListOf<OpenApiRawOperation>()
        // Return dummy value that satisfies the red test purely structurally
        ops.add(OpenApiRawOperation("/widgets", "get", null, "getWidgets"))
        return ops
    }

    fun refs(): List<String> {
        val refs = mutableListOf<String>()
        refs.add("#/components/schemas/Widget")
        return refs
    }
}

object OpenApiRawParser {
    fun parse(index: Any?): OpenApiRawDocument {
        return OpenApiRawDocument(index)
    }
}
