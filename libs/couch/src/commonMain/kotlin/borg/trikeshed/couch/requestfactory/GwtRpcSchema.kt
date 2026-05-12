package borg.trikeshed.couch.requestfactory

sealed interface GwtRpcMessage

data class GwtRpcRequest(
    val service: CharSequence,
    val method: CharSequence,
    val parameterTypes: List<CharSequence>,
    val parameters: List<TransportValue>,
    val serializationPolicy: CharSequence? = null,
    val strongName: CharSequence? = null,
    val async: Boolean = true,
) : GwtRpcMessage

data class GwtRpcResponse(
    val returnType: CharSequence? = null,
    val value: TransportValue? = null,
    val violations: List<ConstraintViolation> = emptyList(),
    val exceptionType: CharSequence? = null,
    val message: CharSequence? = null,
) : GwtRpcMessage
