package borg.trikeshed.util.oroboros

import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.userspace.nio.file.spi.FileOperations

object ForgeHome {
    // Canonical Rook storage root.
    // Resolution order:
    //   1. OROBOROS_HOME environment variable (override for deployments/tests)
    //   2. $HOME/.local/forge sibling storage, kept distinct from any
    //      project-local forge_home-style directories.
    // The default location lives alongside other .local state
    // (forge cas, agents, oroboros manifests). No additional residency is created.
    val defaultHome: String
        get() {
            val override = SystemOperations.default.getenv("OROBOROS_HOME")
            if (!override.isNullOrBlank()) return override
            return SystemOperations.default.homedir + "/.local/forge"
        }

    fun resolve(namespace: String, fileOps: FileOperations): String {
        return resolveSafe(defaultHome, namespace, fileOps)
    }

    fun resolveAgentPath(namespace: String, path: String, fileOps: FileOperations): String {
        val agentBase = resolve(namespace, fileOps)
        return resolveSafe(agentBase, path, fileOps)
    }

    fun resolveSafe(base: String, path: String, fileOps: FileOperations): String {
        val segments = path.split("/")

        if (path.startsWith("/")) {
            throw IllegalArgumentException("Absolute paths are not allowed")
        }

        for (segment in segments) {
            if (segment.isEmpty() && path.isNotEmpty()) {
                throw IllegalArgumentException("Empty segments are not allowed")
            }
            if (segment == "..") {
                throw IllegalArgumentException("Path traversal (..) is not allowed")
            }
            // Allow .oroboros as a valid first segment for forge internal paths
            if ((segment == ".git" || segment == ".pijul") ||
                (segment == ".oroboros" && !path.startsWith(".oroboros/manifests/") && !path.startsWith(".oroboros/status") && !path.startsWith(".oroboros/agents/"))) {
                throw IllegalArgumentException("Access to reserved directories (.git, .pijul, .oroboros) is not allowed")
            }
        }

        val validSegments = segments.filter { it.isNotEmpty() }

        // If empty path, just return base
        if (validSegments.isEmpty()) return base

        val joined = validSegments.joinToString("/")
        return fileOps.resolvePath(base, joined)
    }
}

