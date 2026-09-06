package borg.trikeshed.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The agent's environment is a whitelist: no key crosses, PATH is built, git never smudges or prompts. */
class AgentEnvironmentTest {
    @Test
    fun onlyTheWhitelistCrossesAndGitIsPinned() {
        val host = mapOf("HOME" to "/Users/x", "TERM" to "xterm", "OPENAI_API_KEY" to "sk-secret", "ZAI_API_KEY" to "k", "PATH" to "/evil:/usr/bin", "LANG" to "en_US.UTF-8")
        val env = AgentEnvironment.build(host, listOf("/opt/homebrew/bin", "/opt/homebrew/bin"), AgentEnvironment.GIT)
        assertEquals("/Users/x", env["HOME"]); assertEquals("xterm", env["TERM"]); assertEquals("en_US.UTF-8", env["LANG"])
        assertFalse(env.keys.any { it.contains("KEY") }, env.keys.toString())
        assertEquals("/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin", env["PATH"], "the CLI dirs first, deduplicated, then the system dirs; never the daemon's PATH")
        assertEquals("1", env["NO_COLOR"]); assertEquals("1", env["CI"])
        assertEquals("1", env["GIT_LFS_SKIP_SMUDGE"]); assertEquals("0", env["GIT_TERMINAL_PROMPT"])
        val dirs = AgentCli.candidateDirs(home = "/Users/x", path = "/tmp/cmux-cli-shims:/opt/homebrew/bin:/usr/local/go/bin", tmp = "/tmp")
        assertEquals("/opt/homebrew/bin", dirs.first())
        assertTrue("/tmp/cmux-cli-shims" !in dirs && "/usr/local/go/bin" in dirs, dirs.toString())
    }
}
