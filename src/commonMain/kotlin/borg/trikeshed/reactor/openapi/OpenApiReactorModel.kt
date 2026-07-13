package borg.trikeshed.reactor.openapi

// ── resolved schema model ────────────────────────────────────────────────────

/**
 * A fully resolved OpenAPI schema — $ref pointers are eliminated,
 * allOf/anyOf/oneOf are normalized to flat variant lists,
 * and nested schemas are inlined.
 *
 * NOTE: BooleanSchema avoids name collision with kotlin.Boolean.
 */
sealed interface ResolvedSchema {
    data class Str(
        val format: String? = null,
        val enum: List<String>? = null,
        val default: String? = null,
        val description: String? = null,
    ) : ResolvedSchema

    data class Num(
        val format: String? = null,
        val enum: List<Number>? = null,
        val default: Number? = null,
        val description: String? = null,
    ) : ResolvedSchema

    data class Int(
        val format: String? = null,
        val enum: List<Long>? = null,
        val default: Long? = null,
        val description: String? = null,
    ) : ResolvedSchema

    /** Named BoolSchema to avoid collision with kotlin.Boolean */
    data class BoolSchema(
        val default: Boolean? = null,
        val description: String? = null,
    ) : ResolvedSchema

    data class Obj(
        val properties: List<Prop> = emptyList(),
        val required: Set<String> = emptySet(),
        val additionalProperties: Boolean = true,
        val description: String? = null,
    ) : ResolvedSchema

    data class Arr(
        val items: ResolvedSchema,
        val description: String? = null,
    ) : ResolvedSchema

    /** placeholders for unresolvable or polymorphic schemas */
    data class Generic(val description: String? = null) : ResolvedSchema

    data class Ref(
        val ref: String,
        val target: ResolvedSchema?,
    ) : ResolvedSchema

    data class Variant(
        val schema: ResolvedSchema,
        val description: String? = null,
    ) : ResolvedSchema

    data class Prop(
        val name: String,
        val schema: ResolvedSchema,
        val required: Boolean = false,
        val description: String? = null,
    )
}

// ── resolved operation model ─────────────────────────────────────────────────

/**
 * A resolved operation — all refs resolved, request/response schemas materialised,
 * security flattened.
 */
data class ResolvedOperation(
    val path: String,
    val method: String,
    val operationId: String,
    val summary: String?,
    val description: String?,
    val tags: List<String>,
    val parameters: List<ResolvedParameter>,
    val requestBody: ResolvedRequestBody?,
    val responses: List<ResolvedResponse>,
    val security: List<SecurityRequirement>,
    val isSupervisor: Boolean = false,
)

data class ResolvedParameter(
    val name: String,
    val location: String,   // path | query | header | cookie
    val required: Boolean,
    val schema: ResolvedSchema,
    val description: String? = null,
    val example: Any? = null,
)

data class ResolvedRequestBody(
    val required: Boolean,
    val contentTypes: List<ContentType>,
)

data class ContentType(
    val mediaType: String,
    val schema: ResolvedSchema,
    val example: Any? = null,
)

data class ResolvedResponse(
    val statusCode: Int,
    val description: String?,
    val contentTypes: List<ContentType>,
    val isDefault: Boolean = false,
)

data class SecurityRequirement(
    val schemeName: String,
    val scopes: List<String> = emptyList(),
)

// ── context binding model ────────────────────────────────────────────────────

data class ContextBinding(
    val name: String,
    val keyFqn: String,
    val elementFqn: String,
    val openFqn: String,
) {
    val keySimple get() = keyFqn.substringAfterLast('.')
    val elementSimple get() = elementFqn.substringAfterLast('.')
    val elementImport get() = elementFqn
    val openImport get() = openFqn
    val openAlias get() = "open_${name}"
}

// ── Trikeshed extension model ────────────────────────────────────────────────

/**
 * Parsed x-trikeshed-context extension.
 * Each binding maps a named reactor context to its Key/Element/Open FQN.
 */
data class TrikeshedContext(
    val clientBindings: List<ContextBinding>,
    val serverBindings: List<ContextBinding>,
    val supervisorOperationIds: List<String>,
)

// ── resolved document ────────────────────────────────────────────────────────

data class ResolvedOpenApiDocument(
    val rawRoot: Map<String, Any?>,
    val title: String,
    val version: String,
    val description: String?,
    val servers: List<String>,
    val operations: List<ResolvedOperation>,
    val trikeshedContext: TrikeshedContext?,
    val trikeshedTitle: String?,
) {
    val operationsById: Map<String, ResolvedOperation> by lazy {
        operations.associateBy { it.operationId }
    }

    val supervisorOperations: List<ResolvedOperation> by lazy {
        operations.filter { it.isSupervisor }
    }

    val publicOperations: List<ResolvedOperation> by lazy {
        operations.filter { !it.isSupervisor }
    }
}
