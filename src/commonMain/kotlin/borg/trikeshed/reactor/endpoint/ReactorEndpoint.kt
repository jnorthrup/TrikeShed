package borg.trikeshed.reactor.endpoint

import borg.trikeshed.cursor.Cursor

typealias WireVerb = String
typealias WirePayload = ByteArray

/** Endpoint adapter — invoke(action, pathCursor?) → result. */
interface ReactorEndpoint {
    suspend fun invoke(action: ReactorActionEnvelope, pathCursor: Cursor? = null): ReactorActionEnvelope
}
