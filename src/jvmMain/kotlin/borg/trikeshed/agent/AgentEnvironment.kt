package borg.trikeshed.agent

import java.io.File

/**
 * The environment a coding agent is spawned with: a WHITELIST, never the daemon's own.
 * The daemon's process environment carries provider keys (the 2eea98391 env-leak lesson);
 * an agent gets its locale, its home (its own config and credentials live there), a
 * temp dir, colour off, and a PATH built from the directories the roster resolved plus
 * the system ones. Nothing else crosses.
 */
object AgentEnvironment {
    val PASSED: List<String> = listOf("TERM", "LANG", "LC_ALL", "HOME", "USER", "LOGNAME", "TMPDIR", "SHELL")
    val SYSTEM_PATH: List<String> = listOf("/usr/bin", "/bin", "/usr/sbin", "/sbin")

    /**
     * For the lane's git steps and the agent alike: a scratch clone keeps LFS pointers, never the
     * blobs (the repo tracks a movie; smudging it needs git-lfs on PATH and buys nothing), and git
     * must never stop to ask for credentials.
     */
    val GIT: Map<String, String> = mapOf("GIT_LFS_SKIP_SMUDGE" to "1", "GIT_TERMINAL_PROMPT" to "0")

    fun build(source: Map<String, String>, cliDirs: List<String>, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (k in PASSED) source[k]?.let { out[k] = it }
        out["NO_COLOR"] = "1"
        out["CI"] = "1"
        out["PATH"] = (cliDirs + SYSTEM_PATH).map { File(it).path }.distinct().joinToString(File.pathSeparator)
        out.putAll(extra)
        return out
    }
}
