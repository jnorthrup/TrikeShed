package borg.trikeshed.hyperspace

import modelmux.acp.AcpMessage
import modelmux.acp.AcpResponse
import borg.trikeshed.lib.Series

/**
 * HyperspaceRoutine — embodied hyperspace without eval.
 *
 * Lives **inside the subVM** (`InProcessIsolate` / `ProcessIsolate`). The only
 * contract is `chat` — no tools, no AcpTool, no eval. User and model talk
 * directly; the host only transports.
 *
 * Common interface: no Graal, no Htx, no KeyMux. The JVM actual (`JvmHyperspaceRoutine`)
 * resolves raw keys via KeyMux and membranes the LLM call back to the host.
 */
interface HyperspaceRoutine : AutoCloseable {
    val modelId: String
    /** Embodied chat — no tools, no eval, just messages → response. Runs inside the subVM. */
    suspend fun chat(messages: Series<AcpMessage>): AcpResponse
}
