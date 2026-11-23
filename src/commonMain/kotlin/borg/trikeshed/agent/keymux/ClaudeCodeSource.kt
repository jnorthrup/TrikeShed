package borg.trikeshed.agent.keymux

import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyMap
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.datetime.Clock

/**
 * `llm.anthropic.key` from Claude Code's own OAuth login, read as Hermes reads it (agent/anthropic_credentials.py
 * `read_claude_code_credentials`): the macOS Keychain item `Claude Code-credentials` and `$CLAUDE_CONFIG_DIR|~/.claude`
 * `/.credentials.json`, both `{"claudeAiOauth": {"accessToken", "refreshToken", "expiresAt" (epoch ms)}}`. When both exist the
 * only unexpired one wins, else the later `expiresAt`. A token inside its last 60 s is expired, as in Hermes. Never written:
 * the refresh token is single-use and belongs to whichever of Claude Code and Hermes rotates it.
 */
class ClaudeCodeSource(
    private val explicitFileOps: FileOperations? = null,
    private val explicitProcessOps: ProcessOperations? = null,
) : KeySource() {
    override val name = "claude-code-oauth"

    private class Login(val access: String, val expiresAt: Long) {
        fun fresh(now: Long) = if (expiresAt > 0) now < expiresAt - 60_000 else access.isNotEmpty()
    }

    override suspend fun read(path: KeyPath): String? {
        if (path.size != 3 || path[0] != "llm" || path[1] != "anthropic" || path[2] != "key") return null
        val now = Clock.System.now().toEpochMilliseconds()
        val keychain = keychain()
        val file = file()
        val pick = when {
            keychain == null || file == null -> keychain ?: file
            keychain.fresh(now) != file.fresh(now) -> if (keychain.fresh(now)) keychain else file
            keychain.expiresAt >= file.expiresAt -> keychain
            else -> file
        }
        return pick?.takeIf { it.fresh(now) }?.access
    }

    override suspend fun write(path: KeyPath, value: String): Unit =
        throw UnsupportedOperationException("Claude Code's login is read-only from this source")

    private fun login(json: String): Login? {
        val oauth = runCatching { reifyMap(json) }.getOrNull()?.get("claudeAiOauth") as? Map<*, *> ?: return null
        val access = (oauth["accessToken"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        return Login(access, (oauth["expiresAt"] as? Number)?.toLong() ?: 0L)
    }

    private suspend fun keychain(): Login? {
        val ops = explicitProcessOps ?: currentCoroutineContext()[ProcessOperations.Key] ?: return null
        val out = runCatching { ops.exec("security", listOf("find-generic-password", "-s", "Claude Code-credentials", "-w")) }.getOrNull()
            ?: return null
        return if (out.exitCode == 0) login(out.stdout.decodeToString().trim()) else null
    }

    private suspend fun file(): Login? {
        val ops = explicitFileOps ?: currentCoroutineContext()[FileOperations.Key] ?: return null
        val root = SystemOperations.default.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }
        val path = if (root != null) ops.resolvePath(root, ".credentials.json") else ops.resolvePath("~", ".claude", ".credentials.json")
        return if (ops.isFile(path)) login(ops.readString(path)) else null
    }
}
