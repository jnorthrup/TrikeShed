package borg.trikeshed.reactor

sealed class ReactorError(override val message: String) : Throwable(message) {
    object IdentityNotUnit : ReactorError("TransformCode.Identity is not the unit under project()")
    data class UnknownProtocol(val id: UByte) : ReactorError("Unknown protocol id $id")
    data class ProtocolMismatch(val expected: Protocol, val actual: Protocol) :
        ReactorError("Protocol mismatch: expected ${expected.name}, got ${actual.name}")
    data class SessionClosed(val spec: UByte) : ReactorError("Session for spec $spec is Closed")
    object EmptyPayload : ReactorError("Channel payload was empty")
}
