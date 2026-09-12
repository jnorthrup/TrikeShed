package borg.trikeshed.agent

/**
 * JVM bootstrap of the agent roster probe: Process/`--version` execution is
 * JVM IO, so the probe lives here while the vocabulary (ids, defaults) is
 * commonMain ([AgentRegistry]).
 */
object AgentCliProbe {
    suspend fun probe(enabled: Set<String>, known: List<String> = AgentRegistry.KNOWN) =
        AgentCli.probe(enabled)
}
