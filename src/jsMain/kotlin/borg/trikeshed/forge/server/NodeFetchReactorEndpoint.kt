package borg.trikeshed.forge.server

import borg.trikeshed.reactor.ReactorAction
import borg.trikeshed.reactor.ReactorEndpoint
import borg.trikeshed.reactor.ReactorResult

class NodeFetchReactorEndpoint(
    private val baseUrl: String
) : ReactorEndpoint {
    private val endpoint = NodeReactorEndpoint(baseUrl)

    override suspend fun invoke(action: ReactorAction): ReactorResult {
        return endpoint.invoke(action)
    }
}
