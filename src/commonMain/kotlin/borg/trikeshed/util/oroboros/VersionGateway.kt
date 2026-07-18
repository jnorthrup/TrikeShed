package borg.trikeshed.util.oroboros

import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations

interface VersionGateway {
    /**
     * Initializes the version control repository in the given home directory.
     */
    suspend fun init(home: String): Boolean

    /**
     * Commits all changes in the repository with the given author and message.
     * Returns the new revision identifier.
     */
    suspend fun record(home: String, author: String, message: String): String?

    /**
     * Returns whether this gateway is available on the system.
     */
    suspend fun isAvailable(): Boolean
}

class GitVersionGateway(val processOps: ProcessOperations) : VersionGateway {
    override suspend fun isAvailable(): Boolean {
        return try {
            val res = processOps.exec("git", listOf("--version"))
            res.exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun init(home: String): Boolean {
        val res = processOps.exec("git", listOf("-C", home, "init"))
        return res.exitCode == 0
    }

    override suspend fun record(home: String, author: String, message: String): String? {
        // Add all files
        val addRes = processOps.exec("git", listOf("-C", home, "add", "."))
        if (addRes.exitCode != 0) return null

        // Check if there's anything to commit
        val statusRes = processOps.exec("git", listOf("-C", home, "status", "--porcelain"))
        if (statusRes.exitCode != 0 || statusRes.stdout.isEmpty()) {
            return getCurrentRevision(home)
        }

        // Parse author into name and email (e.g., "Agent <agent@example.com>")
        // Git requires explicit author config per commit if no global config exists
        val name = if (author.contains("<")) author.substringBefore("<").trim() else author
        val email = if (author.contains("<")) author.substringAfter("<").substringBefore(">").trim() else "agent@trikeshed.local"
        val commitRes = processOps.exec(
            "git",
            listOf(
                "-c", "user.name=$name",
                "-c", "user.email=$email",
                "-C", home, "commit", "--author=$author", "-m", message
            )
        )
        if (commitRes.exitCode != 0) return null

        return getCurrentRevision(home)
    }

    private suspend fun getCurrentRevision(home: String): String? {
        val res = processOps.exec("git", listOf("-C", home, "rev-parse", "HEAD"))
        if (res.exitCode == 0) {
            return res.stdout.decodeToString().trim()
        }
        return null
    }
}

class PijulVersionGateway(val processOps: ProcessOperations) : VersionGateway {
    override suspend fun isAvailable(): Boolean {
        return try {
            val res = processOps.exec("pijul", listOf("--version"))
            res.exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun init(home: String): Boolean {
        val res = processOps.exec("pijul", listOf("init", "--repository", home))
        return res.exitCode == 0
    }

    override suspend fun record(home: String, author: String, message: String): String? {
        val addRes = processOps.exec(
            "pijul",
            listOf("add", "--repository", home, ".")
        )
        if (addRes.exitCode != 0) return null

        val recordRes = processOps.exec(
            "pijul",
            listOf("record", "--repository", home, "-a", "-m", message)
        )
        if (recordRes.exitCode != 0) return null

        return getCurrentRevision(home)
    }

    private suspend fun getCurrentRevision(home: String): String? {
        val res = processOps.exec("pijul", listOf("log", "--repository", home, "--limit", "1"))
        if (res.exitCode == 0) {
            return res.stdout.decodeToString().trim().lines().firstOrNull()
        }
        return null
    }
}

/** @deprecated use the interface directly; kept only for source compatibility. */
@Suppress("unused")
fun VersionGateway(processOps: ProcessOperations, usePijul: Boolean = false): VersionGateway =
    if (usePijul) PijulVersionGateway(processOps) else GitVersionGateway(processOps)
