package borg.trikeshed.util.oroboros

import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.userspace.nio.file.spi.FileOperations

object ForgeHome {
    val defaultHome: String
        get() = "${SystemOperations.default.homedir}/.local/forge_home"

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
            if (segment == ".git" || segment == ".pijul" || segment == ".oroboros") {
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
