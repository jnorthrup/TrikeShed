package borg.trikeshed.couch.requestfactory

data class RequestFactorySchema(
    val factory: RequestFactorySpec,
    val contexts: List<RequestContextSpec>,
    val proxies: List<ProxySpec>,
    val validation: List<ValidationRuleSpec> = emptyList(),
)

data class RequestFactorySpec(
    val name: CharSequence,
    val servletPath: CharSequence = "/gwtRequest",
    val requestPath: CharSequence = "/gwtRequest",
    val eventBusType: CharSequence? = null,
)

data class RequestContextSpec(
    val name: CharSequence,
    val serviceType: CharSequence,
    val serviceAnnotation: ServiceAnnotation? = null,
    val methods: List<RequestMethodSpec>,
)

enum class ServiceAnnotation {
    Service,
    ServiceName,
}

data class RequestMethodSpec(
    val name: CharSequence,
    val kind: RequestMethodKind,
    val returnType: CharSequence,
    val parameters: List<ParameterSpec>,
)

enum class RequestMethodKind {
    Request,
    InstanceRequest,
}

data class ParameterSpec(
    val name: CharSequence,
    val type: CharSequence,
    val annotations: List<CharSequence> = emptyList(),
)

sealed interface ProxySpec {
    val name: CharSequence
    val serverType: CharSequence
    val extraTypes: List<CharSequence>
    val properties: List<PropertySpec>
}

data class EntityProxySpec(
    override val name: CharSequence,
    override val serverType: CharSequence,
    val idProperty: CharSequence? = null,
    val versionProperty: CharSequence? = null,
    override val extraTypes: List<CharSequence> = emptyList(),
    override val properties: List<PropertySpec>,
) : ProxySpec

data class ValueProxySpec(
    override val name: CharSequence,
    override val serverType: CharSequence,
    override val extraTypes: List<CharSequence> = emptyList(),
    override val properties: List<PropertySpec>,
) : ProxySpec

data class PropertySpec(
    val name: CharSequence,
    val type: CharSequence,
    val readOnly: Boolean = false,
)

data class ValidationRuleSpec(
    val targetType: CharSequence,
    val constraint: CharSequence,
    val message: CharSequence? = null,
)
