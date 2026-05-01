package borg.trikeshed.couch.requestfactory

object RequestFactoryTransportContract {
    const val PATH = "/gwtRequest"
    const val CONTENT_TYPE = "application/json"
    const val OPERATION_ID = "invokeRequestFactory"
}

data class RequestFactoryCall(
    val context: String,
    val method: String,
    val arguments: List<TransportValue>,
    val ccekKey: String? = null,
)

data class RequestFactoryResponse(
    val success: Boolean,
    val value: TransportValue? = null,
    val updatedEntities: List<EntityDelta> = emptyList(),
    val validationErrors: List<ConstraintViolation> = emptyList(),
)

data class EntityDelta(
    val type: String,
    val id: TransportValue? = null,
    val version: TransportValue? = null,
    val properties: Map<String, TransportValue> = emptyMap(),
)

data class ConstraintViolation(
    val path: String,
    val message: String,
    val invalidValue: TransportValue? = null,
)

data class ConstraintViolationResponse(
    val errors: List<ConstraintViolation>,
)

data class ErrorResponse(
    val message: String,
    val details: String? = null,
)

interface RequestFactoryTransportService {
    fun invoke(call: RequestFactoryCall): RequestFactoryResponse
}
