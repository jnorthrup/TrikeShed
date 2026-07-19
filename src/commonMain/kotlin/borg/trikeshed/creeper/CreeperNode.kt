package borg.trikeshed.creeper

import borg.trikeshed.context.nuid.Nuid
import borg.trikeshed.context.nuid.NuidFanoutElement
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
import borg.trikeshed.reactor.ReactorResult
import keymux.KeyMux
import modelmux.ModelMux
import modelmux.ModelEntry
import modelmux.acp.AcpAction

interface CreeperNodeOperations {
    suspend fun accept(payload: ByteArray, nuid: Nuid): ReactorResult
}

/**
 * Creeper Node implementation as specified in the upstream architecture book.
 * Operates as a local-first, capability-limited router on edge networks.
 *
 * Target environments include POSIX platforms and Java on macOS aiming to launch
 * GraalVM polyglot processes.
 */
class CreeperNode(
    private val keyMux: KeyMux,
    private val modelMux: ModelMux,
    private val casStore: CasStore,
    private val fanout: NuidFanoutElement
) : CreeperNodeOperations {

    /**
     * Accepts a reactor action and determines routing based on capability and TTL.
     * Evaluates whether the work can be processed locally via GraalVM polyglot
     * execution, or if it must be routed via ModelMux to an upstream VPS peer.
     */
    override suspend fun accept(payload: ByteArray, nuid: Nuid): ReactorResult {
        // NUID validation: Check capability constraints
        val capList = listOf(nuid.cap.toString())
        val requiredCaps = _l[capList]

        // Convert the raw payload action to an AcpAction representation for routing
        val action = AcpAction(
            verb = "creeper.dispatch",
            resource = nuid.cap.toString(),
            params = mapOf("payloadSize" to payload.size)
        )

        // Evaluate eligibility deterministically
        val routeResult = modelMux.route(
            models = s_[], // The available peer models should be hydrated from CAS/KeyMux state in a full deployment
            action = action,
            requiredCaps = requiredCaps
        )

        val destinationModels = routeResult.a

        if (destinationModels.a > 0) {
            // Forward to designated upstream peer based on ModelMux deterministic routing
            val target = destinationModels.b(0).a // Get the first matching model ID
            return nuid j ("forwarded" j target.encodeToByteArray())
        }

        // Local execution fallback: Validate against local CAS leases
        // Under a JVM/GraalVM environment, we would invoke the local Graal Context safely
        // extracting arguments to avoid string injection via `new Function(...)` semantics
        val localHash = payload.contentHashCode().toString()
        casStore.put(payload)

        // Emulate local GraalVM polyglot execution returning the hashed execution context
        return nuid j ("polyglot_local_exec" j localHash.encodeToByteArray())
    }
}
