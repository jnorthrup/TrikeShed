package borg.trikeshed.couch.requestfactory

sealed interface GwtRpcMessage

data class GwtRpcRequest(
    val service: String,
    val method: String,
    val parameterTypes: List<String>,
    val parameters: List<TransportValue>,
    val serializationPolicy: String? = null,
    val strongName: String? = null,
    val async: Boolean = true,
) : GwtRpcMessage

data class GwtRpcResponse(
    val returnType: String? = null,
    val value: TransportValue? = null,
    val violations: List<ConstraintViolation> = emptyList(),
    val exceptionType: String? = null,
    val message: String? = null,
) : GwtRpcMessage
