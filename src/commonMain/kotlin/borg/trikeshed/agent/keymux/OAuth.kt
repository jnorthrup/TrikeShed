package borg.trikeshed.agent.keymux

import borg.trikeshed.parse.reifyMap
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import kotlinx.coroutines.currentCoroutineContext
import kotlin.io.encoding.Base64

/**
 * The OAuth logins that Hermes borrows (agent/anthropic_credentials.py, agent/anthropic_adapter.py,
 * agent/codex_headers.py, hermes_cli/auth_nous.py), as far as they reach a request: what a token is, when it dies, and
 * which identity it must ride with. Refresh tokens are single-use and Hermes, Claude Code and the Codex CLI each rotate
 * them under their own lock, so nothing here refreshes or writes a login that one of them owns.
 */
object OAuth {
    /** An OAuth access token is a JWT: its payload says when it dies and, for Codex, which workspace it speaks for. */
    fun claims(token: String): Map<String, Any?>? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        return runCatching {
            reifyMap(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(parts[1]).decodeToString())
        }.getOrNull()
    }

    /** The JWT's `exp` in epoch milliseconds; null for a token that is not a JWT or carries none. */
    fun expiresAtMs(token: String): Long? = (claims(token)?.get("exp") as? Number)?.toLong()?.times(1000)

    /**
     * `_is_oauth_token`: the Console key families (`sk-ant-api`, `-usr`, `-svc`, `-admin`) pay per token and authenticate as
     * x-api-key; any other `sk-ant-`, a JWT (`eyJ`) or `cc-` is a Claude Code bearer.
     */
    fun isClaudeBearer(key: String): Boolean =
        listOf("sk-ant-api", "sk-ant-usr", "sk-ant-svc", "sk-ant-admin").none { key.startsWith(it) } &&
            listOf("sk-ant-", "eyJ", "cc-").any { key.startsWith(it) }

    /** Anthropic refuses a Claude Code bearer whose user-agent version trails the release, so the installed CLI's is used. */
    const val CLAUDE_CODE_VERSION_FALLBACK = "2.1.74"

    var claudeCodeVersion: String = CLAUDE_CODE_VERSION_FALLBACK
        private set
    private var detected = false

    /** `claude --version` ("2.1.74 (Claude Code)"), once per process; the fallback stands where no process can be run. */
    suspend fun detectClaudeCode() {
        if (detected) return
        detected = true
        val ops = currentCoroutineContext()[ProcessOperations.Key] ?: return
        val out = runCatching { ops.exec("claude", listOf("--version")) }.getOrNull() ?: return
        val version = out.stdout.decodeToString().trim().substringBefore(' ')
        if (out.exitCode == 0 && version.firstOrNull()?.isDigit() == true) claudeCodeVersion = version
    }
}
