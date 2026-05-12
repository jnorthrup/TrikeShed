package borg.trikeshed.couch.requestfactory

object RequestFactoryTransportContract {
    const val PATH = "/gwtRequest"
    const val CONTENT_TYPE = "application/json"
    const val OPERATION_ID = "invokeRequestFactory"
}

data class RequestFactoryCall(
    val context: CharSequence,
    val method: CharSequence,
    val arguments: List<TransportValue>,
    val ccekKey: CharSequence? = null,
)

data class RequestFactoryResponse(
    val success: Boolean,
    val value: TransportValue? = null,
    val updatedEntities: List<EntityDelta> = emptyList(),
    val validationErrors: List<ConstraintViolation> = emptyList(),
)

data class EntityDelta(
    val type: CharSequence,
    val id: TransportValue? = null,
    val version: TransportValue? = null,
    val properties: Map<CharSequence, TransportValue> = emptyMap(),
)

data class ConstraintViolation(
    val path: CharSequence,
    val message: CharSequence,
    val invalidValue: TransportValue? = null,
)

data class ConstraintViolationResponse(
    val errors: List<ConstraintViolation>,
)

data class ErrorResponse(
    val message: CharSequence,
    val details: CharSequence? = null,
)

interface RequestFactoryTransportService {
    fun invoke(call: RequestFactoryCall): RequestFactoryResponse
}
