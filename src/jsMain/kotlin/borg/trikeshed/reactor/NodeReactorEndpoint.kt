package borg.trikeshed.reactor

import borg.trikeshed.forge.server.HttpForwarderSpec
import borg.trikeshed.forge.server.NodeHttpForwarder

class NodeReactorEndpoint(
    private val baseUrl: String,
) : ReactorEndpoint {
    override suspend fun invoke(action: ReactorAction): ReactorResult {
        val spec = HttpForwarderSpec(
            verb = "POST",
            path = "/api/invoke",
            headers = mapOf("Content-Type" to "application/octet-stream"),
            body = ReactorJsonCodec.encode(action),
        )
        val response = NodeHttpForwarder.send(baseUrl, spec)
        if (response.status != 200) throw RuntimeException("reactor returned ${response.status}")
        return ReactorJsonCodec.decode(response.body)
    }
}
