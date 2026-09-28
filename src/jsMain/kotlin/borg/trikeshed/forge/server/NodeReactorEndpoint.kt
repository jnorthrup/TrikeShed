package borg.trikeshed.forge.server

import borg.trikeshed.reactor.ReactorAction
import borg.trikeshed.reactor.ReactorEndpoint
import borg.trikeshed.reactor.ReactorResult
import borg.trikeshed.reactor.json
import borg.trikeshed.reactor.reactorEnvelope
import borg.trikeshed.reactor.toConfixEnvelope
import borg.trikeshed.reactor.toReactorAction

// Polyfill atob and btoa for encoding/decoding in JS
external fun btoa(s: String): String
external fun atob(s: String): String

class NodeReactorEndpoint(
    private val baseUrl: String,        // e.g., "http://localhost:8088"
) : ReactorEndpoint {
    override suspend fun invoke(action: ReactorAction): ReactorResult {
        val spec = HttpForwarderSpec(
            verb = "POST",
            path = "/api/invoke",
            headers = mapOf("Content-Type" to "application/octet-stream"),
            body = action.toConfixEnvelope().json(),
        )
        val response = NodeHttpForwarder.send(baseUrl, spec)
        if (response.status != 200) throw RuntimeException("reactor returned ${response.status}")
        return reactorEnvelope(response.body).toReactorAction()
    }
}
