package borg.trikeshed.reactor

import borg.trikeshed.context.nuid.Nuid
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

typealias Verb = String
typealias Payload = ByteArray

typealias ReactorAction = Join<Nuid, Join<Verb, Payload>>
typealias ReactorResult = Join<Nuid, Join<Verb, Payload>>

interface ReactorEndpoint {
    suspend fun invoke(action: ReactorAction): ReactorResult
}
