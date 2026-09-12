package borg.trikeshed.agent

/**
 * The coding-agent roster vocabulary in commonMain: the two CLIs, their ids,
 * and the default enablement. The Process-executing probe stays in the JVM
 * actual ([borg.trikeshed.agent.AgentCli]); the daemon names only this.
 */
object AgentRegistry {
    const val CODEX = "codex"
    const val OPENCODE = "opencode"
    val DEFAULT_ENABLED: Set<String> = setOf(CODEX, OPENCODE)
    val KNOWN: List<String> = listOf(CODEX, OPENCODE)
}
