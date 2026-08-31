package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import keymux.HarnessRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The egress policy gate.
 *
 * These pin the repair described in [EgressAllowlist]: keymux/modelmux resolved
 * keys and built correct requests, and then our OWN substrate refused the
 * socket, because the permitted-host list was a hand-maintained copy in bash
 * sitting next to the provider table that already knew every host. The copies
 * drifted and every call died one layer below where anyone was looking.
 *
 * The first test is the one that matters: it makes the two lists incapable of
 * drifting, because there is now only one.
 */
class EgressAllowlistTest {

    @Test
    fun `every registry provider host is permitted`() {
        // The regression in the flesh: bin/oroboros-daemon said api.moonshot.cn
        // while the registry said api.moonshot.ai, so moonshot and kimi could
        // never connect — and nobody could see why, because the refusal came
        // from inside NIO. Deriving the list makes this class of bug unwritable.
        val missing = mutableListOf<String>()
        for (i in 0 until HarnessRegistry.providers.size) {
            val p = HarnessRegistry.providers[i]
            val url = p.defaultBaseUrl ?: continue
            val host = EgressAllowlist.hostOf(url)
            if (host == null || !EgressAllowlist.permits(host)) missing += "${p.id} → $url"
        }
        assertTrue(
            missing.isEmpty(),
            "these registry providers cannot be reached by the substrate: $missing",
        )
    }

    @Test
    fun `moonshot resolves to the ai host the registry actually names`() {
        // Named explicitly rather than left to the sweep above: this exact
        // spelling is what broke, and a future edit to the registry that
        // reintroduced .cn should fail loudly here.
        assertTrue(EgressAllowlist.permits("api.moonshot.ai"))
    }

    @Test
    fun `baseline service hosts stay permitted`() {
        for (h in listOf("127.0.0.1", "localhost", "github.com", "jules.googleapis.com")) {
            assertTrue(EgressAllowlist.permits(h), "$h must remain permitted")
        }
    }

    @Test
    fun `deny by default is preserved`() {
        // The repair widens the list to the providers this product exists to
        // talk to. It must NOT open egress generally — that was the point of
        // the original gate and it is kept.
        assertFalse(EgressAllowlist.permits("evil.example.com"))
        assertFalse(EgressAllowlist.permits("169.254.169.254")) // cloud metadata
    }

    @Test
    fun `allowUrl permits the host a configured base_url names`() {
        assertFalse(EgressAllowlist.permits("gateway.internal.example"))
        EgressAllowlist.allowUrl("https://gateway.internal.example:8443/v1/openai")
        assertTrue(EgressAllowlist.permits("gateway.internal.example"))
    }

    @Test
    fun `hostOf strips scheme port path and userinfo`() {
        assertEquals("api.groq.com", EgressAllowlist.hostOf("https://api.groq.com/openai/v1"))
        assertEquals("api.z.ai", EgressAllowlist.hostOf("https://api.z.ai:443/api/paas/v4"))
        assertEquals("h.example", EgressAllowlist.hostOf("https://user:pw@h.example/v1"))
        // A bare host is a legal operator entry, not a URL.
        assertEquals("api.x.ai", EgressAllowlist.hostOf("api.x.ai"))
        assertNull(EgressAllowlist.hostOf(""))
        // Must not throw on a malformed base_url — this runs on the connect path.
        assertNull(EgressAllowlist.hostOf("https://"))
    }

    @Test
    fun `refusal names the remedy not just the verdict`() {
        val msg = EgressAllowlist.refusalMessage("nope.example", 443)
        // The old message said only "host not in allowlist", which sent readers
        // into the NIO substrate to discover an allowlist existed at all.
        assertTrue("nope.example:443" in msg, "must name the refused host and port")
        assertTrue("TRIKESHED_EGRESS_ALLOWLIST" in msg, "must name the env var that fixes it")
        assertTrue("HarnessRegistry" in msg, "must name the durable fix")
    }
}
