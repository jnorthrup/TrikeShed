package borg.trikeshed.userspace.reactor.process

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.capability
import borg.trikeshed.lib.j
import borg.trikeshed.reactor.ReactorAction
import borg.trikeshed.reactor.ReactorEndpoint
import borg.trikeshed.reactor.ReactorResult
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations

/**
 * Endpoint for NUID-authorized process spawn/exec over the reactor.
 * Fulfills T12: Process worker.
 */
class ProcessReactorEndpoint(
    private val processOps: ProcessOperations
) : ReactorEndpoint {

    override suspend fun invoke(action: ReactorAction): ReactorResult {
        val nuid = action.a
        val capability = nuid.capability

        if (capability !is Capability.Process) {
            return action.a j ("error" j "Invalid capability for ProcessReactorEndpoint".encodeToByteArray())
        }

        val command = capability.name
        val verb = action.b.a
        val payload = action.b.b

        if (verb != "exec") {
            return action.a j ("error" j "Unsupported verb: $verb".encodeToByteArray())
        }

        val argsText = payload.decodeToString()
        val args = if (argsText.isBlank()) emptyList() else argsText.split("\n")

        val result = try {
            processOps.exec(command, args = args)
        } catch (e: Exception) {
            return action.a j ("error" j (e.message ?: "Unknown error").encodeToByteArray())
        }

        val responseVerb = if (result.exitCode == 0) "ok" else "error"
        val responsePayload = if (result.exitCode == 0) result.stdout else result.stderr

        return action.a j (responseVerb j responsePayload)
    }
}
