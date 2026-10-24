package borg.trikeshed.agent.modelmux

import borg.trikeshed.job.CasStore
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Any use is a test failure: opening an element must not start a process. */
private object NoProcess : ProcessOperations {
    override suspend fun exec(command: String, args: List<String>, stdin: ByteArray?, env: Map<String, String>): ProcessResult =
        fail("no process may be started")
}

class SharedContextRootTest {
    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    @Test
    fun nothingIsConfiguredByDefaultExceptTheReelScopeAndNothingIsFetched() {
        val settings = SharedContextRoot.settings(env())
        assertEquals(setOf("reel/*"), settings.scopes)
        assertNull(settings.peer)
        assertEquals(SharedContextMode.LOCAL, settings.mode)
    }

    @Test
    fun aNamedRepositoryAloneMakesPeerMode() {
        val settings = SharedContextRoot.settings(env(
            SharedContextRoot.SCOPES to "team/a, reel/*",
            SharedContextRoot.KUBO_REPO to "/var/kubo/repo-b",
            SharedContextRoot.KUBO_API to "/ip4/127.0.0.1/tcp/5011",
            SharedContextRoot.KUBO_BIN to "/opt/homebrew/bin/ipfs",
            SharedContextRoot.TIMEOUT_SECONDS to "12",
        ))
        assertEquals(setOf("team/a", "reel/*"), settings.scopes)
        val peer = settings.peer!!
        assertEquals("/var/kubo/repo-b", peer.repo)
        assertEquals("/ip4/127.0.0.1/tcp/5011", peer.api)
        assertEquals("/opt/homebrew/bin/ipfs", peer.binary)
        assertEquals(12, peer.timeoutSeconds)
        assertEquals(SharedContextMode.PEER, settings.mode)
        assertEquals(30, SharedContextRoot.settings(env(SharedContextRoot.KUBO_REPO to "/r")).peer!!.timeoutSeconds, "the default is bounded too")
    }

    @Test
    fun badSettingsFailWhenMadeNotAtTheFirstRequest() {
        fun bad(vararg pairs: Pair<String, String>) = assertFailsWith<IllegalArgumentException>(pairs.joinToString()) { SharedContextRoot.settings(env(*pairs)) }
        bad(SharedContextRoot.KUBO_REPO to "relative/repo")
        bad(SharedContextRoot.KUBO_REPO to "/r", SharedContextRoot.KUBO_API to "/ip4/10.0.0.5/tcp/5001")      // a remote API is not expressible
        bad(SharedContextRoot.KUBO_REPO to "/r", SharedContextRoot.KUBO_API to "http://127.0.0.1:5001")       // not a multiaddr
        bad(SharedContextRoot.KUBO_API to "/ip4/127.0.0.1/tcp/5001")                                         // an API without a named repository
        bad(SharedContextRoot.KUBO_REPO to "/r", SharedContextRoot.TIMEOUT_SECONDS to "soon")
        bad(SharedContextRoot.KUBO_REPO to "/r", SharedContextRoot.TIMEOUT_SECONDS to "0")
        bad(SharedContextRoot.KUBO_REPO to "/r", SharedContextRoot.TIMEOUT_SECONDS to "3601")
        bad(SharedContextRoot.KUBO_REPO to "/r", SharedContextRoot.KUBO_BIN to "ipfs\u0000")
        bad(SharedContextRoot.SCOPES to " , ")                                                               // naming nothing is not "everything"
        bad(SharedContextRoot.SCOPES to "team/a\u0007")
    }

    @Test
    fun anElementOpenedFromSettingsServesExactlyThoseScopes() = runTest {
        val local = SharedContextRoot.open("root-local", CasStore.inMemory(), SharedContextSettings(setOf("team/a", "reel/*")), NoProcess)
        try {
            assertEquals(SharedContextMode.LOCAL, local.context.policy.mode)
            assertTrue(local.context.policy.serves("team/a"))
            assertTrue(local.context.policy.serves("reel/ace-1"))
            assertTrue(!local.context.policy.serves("team/b"))
        } finally {
            local.drain()
        }
        // PEER mode only names a node; opening the element starts no process and contacts nothing.
        val peer = SharedContextRoot.open("root-peer", CasStore.inMemory(),
            SharedContextSettings(setOf("team/a"), PeerSettings("/var/kubo/repo-b", "/ip4/127.0.0.1/tcp/5011")), NoProcess)
        try {
            assertEquals(SharedContextMode.PEER, peer.context.policy.mode)
        } finally {
            peer.drain()
        }
    }
}
