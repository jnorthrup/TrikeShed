package borg.trikeshed.couch.requestfactory

data class RequestFactorySchema(
    val factory: RequestFactorySpec,
    val contexts: List<RequestContextSpec>,
    val proxies: List<ProxySpec>,
    val validation: List<ValidationRuleSpec> = emptyList(),
)

data class RequestFactorySpec(
    val name: String,
    val servletPath: String = "/gwtRequest",
    val requestPath: String = "/gwtRequest",
    val eventBusType: String? = null,
)

data class RequestContextSpec(
    val name: String,
    val serviceType: String,
    val serviceAnnotation: ServiceAnnotation? = null,
    val methods: List<RequestMethodSpec>,
)

enum class ServiceAnnotation {
    Service,
    ServiceName,
}

data class RequestMethodSpec(
    val name: String,
    val kind: RequestMethodKind,
    val returnType: String,
    val parameters: List<ParameterSpec>,
)

enum class RequestMethodKind {
    Request,
    InstanceRequest,
}

data class ParameterSpec(
    val name: String,
    val type: String,
    val annotations: List<String> = emptyList(),
)

sealed interface ProxySpec {
    val name: String
    val serverType: String
    val extraTypes: List<String>
    val properties: List<PropertySpec>
}

data class EntityProxySpec(
    override val name: String,
    override val serverType: String,
    val idProperty: String? = null,
    val versionProperty: String? = null,
    override val extraTypes: List<String> = emptyList(),
    override val properties: List<PropertySpec>,
) : ProxySpec

data class ValueProxySpec(
    override val name: String,
    override val serverType: String,
    override val extraTypes: List<String> = emptyList(),
    override val properties: List<PropertySpec>,
) : ProxySpec

data class PropertySpec(
    val name: String,
    val type: String,
    val readOnly: Boolean = false,
)

data class ValidationRuleSpec(
    val targetType: String,
    val constraint: String,
    val message: String? = null,
)
