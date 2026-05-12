package borg.trikeshed.openapi

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
        val format: CharSequence? = null,
        val enum: List<CharSequence>? = null,
        val default: CharSequence? = null,
        val description: CharSequence? = null,
    ) : ResolvedSchema

    data class Num(
        val format: CharSequence? = null,
        val enum: List<Number>? = null,
        val default: Number? = null,
        val description: CharSequence? = null,
    ) : ResolvedSchema

    data class Int(
        val format: CharSequence? = null,
        val enum: List<Long>? = null,
        val default: Long? = null,
        val description: CharSequence? = null,
    ) : ResolvedSchema

    /** Named BoolSchema to avoid collision with kotlin.Boolean */
    data class BoolSchema(
        val default: Boolean? = null,
        val description: CharSequence? = null,
    ) : ResolvedSchema

    data class Obj(
        val properties: List<Prop> = emptyList(),
        val required: Set<CharSequence> = emptySet(),
        val additionalProperties: Boolean = true,
        val description: CharSequence? = null,
    ) : ResolvedSchema

    data class Arr(
        val items: ResolvedSchema,
        val description: CharSequence? = null,
    ) : ResolvedSchema

    /** placeholders for unresolvable or polymorphic schemas */
    data class Generic(val description: CharSequence? = null) : ResolvedSchema

    data class Ref(
        val ref: CharSequence,
        val target: ResolvedSchema?,
    ) : ResolvedSchema

    data class Variant(
        val schema: ResolvedSchema,
        val description: CharSequence? = null,
    ) : ResolvedSchema

    data class Prop(
        val name: CharSequence,
        val schema: ResolvedSchema,
        val required: Boolean = false,
        val description: CharSequence? = null,
    )
}

// ── resolved operation model ─────────────────────────────────────────────────

/**
 * A resolved operation — all refs resolved, request/response schemas materialised,
 * security flattened.
 */
data class ResolvedOperation(
    val path: CharSequence,
    val method: CharSequence,
    val operationId: CharSequence,
    val summary: CharSequence?,
    val description: CharSequence?,
    val tags: List<CharSequence>,
    val parameters: List<ResolvedParameter>,
    val requestBody: ResolvedRequestBody?,
    val responses: List<ResolvedResponse>,
    val security: List<SecurityRequirement>,
    val isSupervisor: Boolean = false,
)

data class ResolvedParameter(
    val name: CharSequence,
    val location: CharSequence,   // path | query | header | cookie
    val required: Boolean,
    val schema: ResolvedSchema,
    val description: CharSequence? = null,
    val example: Any? = null,
)

data class ResolvedRequestBody(
    val required: Boolean,
    val contentTypes: List<ContentType>,
)

data class ContentType(
    val mediaType: CharSequence,
    val schema: ResolvedSchema,
    val example: Any? = null,
)

data class ResolvedResponse(
    val statusCode: Int,
    val description: CharSequence?,
    val contentTypes: List<ContentType>,
    val isDefault: Boolean = false,
)

data class SecurityRequirement(
    val schemeName: CharSequence,
    val scopes: List<CharSequence> = emptyList(),
)

// ── context binding model ────────────────────────────────────────────────────

data class ContextBinding(
    val name: CharSequence,
    val keyFqn: CharSequence,
    val elementFqn: CharSequence,
    val openFqn: CharSequence,
) {
    val keySimple get() = keyFqn.toString().substringAfterLast('.')
    val elementSimple get() = elementFqn.toString().substringAfterLast('.')
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
    val supervisorOperationIds: List<CharSequence>,
)

// ── resolved document ────────────────────────────────────────────────────────

data class ResolvedOpenApiDocument(
    val rawRoot: Map<CharSequence, Any?>,
    val title: CharSequence,
    val version: CharSequence,
    val description: CharSequence?,
    val servers: List<CharSequence>,
    val operations: List<ResolvedOperation>,
    val trikeshedContext: TrikeshedContext?,
    val trikeshedTitle: CharSequence?,
) {
    val operationsById: Map<CharSequence, ResolvedOperation> by lazy {
        operations.associateBy { it.operationId }
    }

    val supervisorOperations: List<ResolvedOperation> by lazy {
        operations.filter { it.isSupervisor }
    }

    val publicOperations: List<ResolvedOperation> by lazy {
        operations.filter { !it.isSupervisor }
    }
}
