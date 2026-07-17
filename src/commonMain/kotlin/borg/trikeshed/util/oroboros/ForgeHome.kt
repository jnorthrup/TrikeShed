package borg.trikeshed.util.oroboros

import borg.trikeshed.userspace.nio.platform.spi.SystemOperations

/**
 * Handles confinement of agent workspaces within `~/.local/forge_home/agents/<agent-id>`.
 */
class ForgeHome(
    val sysOps: SystemOperations = SystemOperations.default,
    val basePath: String = "${sysOps.homedir}/.local/forge_home"
) {
    /**
     * Resolves and confines a sub-path for a given agent.
     * Rejects absolute paths, ".." traversals, empty segments, and internal roots (.git, .pijul, .oroboros).
     */
    fun resolveAgentPath(agentId: String, subPath: String): String {
        require(agentId.isNotBlank()) { "Agent ID cannot be empty" }
        require(!agentId.contains("/")) { "Agent ID cannot contain slashes" }
        require(agentId !in listOf(".", "..")) { "Invalid agent ID" }

        if (subPath.startsWith("/")) {
            throw IllegalArgumentException("Absolute paths are rejected")
        }

        val segments = subPath.split("/").filter { it.isNotEmpty() }

        for (segment in segments) {
            if (segment == "..") {
                throw IllegalArgumentException("Path traversal '..' is rejected")
            }
            if (segment == "." || segment == "") {
                continue
            }
        }

        // Check internal roots. These are strictly forbidden at any level to prevent agent breakout or tampering
        // with the version control layer.
        for (segment in segments) {
            if (segment == ".git" || segment == ".pijul" || segment == ".oroboros") {
                throw IllegalArgumentException("Internal roots (.git, .pijul, .oroboros) are rejected")
            }
        }

        val agentHome = "$basePath/agents/$agentId"
        if (segments.isEmpty()) {
            return agentHome
        }
        return "$agentHome/${segments.joinToString("/")}"
    }
}
