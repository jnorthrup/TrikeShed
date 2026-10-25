package borg.trikeshed.mux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpStdioOptionsTest {
    private fun parse(vararg args: String) = McpStdioOptions.parse(arrayOf(*args))
    private fun refused(vararg args: String): String = assertFailsWith<IllegalArgumentException>(args.joinToString(" ")) { parse(*args) }.message.orEmpty()

    @Test
    fun withNoFlagsNothingIsEnabled() {
        val o = parse()
        assertTrue(o.scopes.isEmpty()); assertNull(o.store); assertNull(o.journalDir)
        assertFalse(o.allowWrite); assertFalse(o.allowExec); assertFalse(o.oversightMlx); assertFalse(o.help)
        assertTrue(o.lanes.isEmpty())
        assertEquals(8, o.maxInFlight); assertEquals(1_048_576, o.maxLineBytes)
    }

    @Test
    fun everyCapabilityIsAnExplicitFlag() {
        val o = parse(
            "--scope", "team/a", "--scope", "team/b", "--scope", "team/a", "--store", "/var/ctx", "--allow-write", "--allow-exec",
            "--journal-dir", "/var/journals", "--oversight", "mlx", "--max-in-flight", "3", "--max-line-bytes", "2048",
        )
        assertEquals(setOf("team/a", "team/b"), o.scopes)
        assertEquals("/var/ctx", o.store); assertEquals("/var/journals", o.journalDir)
        assertTrue(o.allowWrite && o.allowExec && o.oversightMlx)
        assertEquals(3, o.maxInFlight); assertEquals(2048, o.maxLineBytes)
        assertTrue(parse("-h").help); assertTrue(parse("--help").help)
    }

    @Test
    fun malformedOrAmbiguousInvocationsAreRefusedWithAPlainReason() {
        assertTrue("unknown option" in refused("--allow-everything"))
        assertTrue("needs a value" in refused("--store"))
        assertTrue("twice" in refused("--store", "/a", "--store", "/b"))
        assertTrue("twice" in refused("--journal-dir", "/a", "--journal-dir", "/b"))
        assertTrue("only: mlx" in refused("--oversight", "gpt"))
        for (bad in listOf("0", "65", "abc", "-1", "")) assertTrue("1..64" in refused("--max-in-flight", bad), bad)
        for (bad in listOf("1023", "16777217", "x")) assertTrue("1024..16777216" in refused("--max-line-bytes", bad), bad)
        assertTrue("printable" in refused("--scope", ""))
        assertTrue("printable" in refused("--scope", " padded "))
        assertTrue("printable" in refused("--scope", "tab\there"))
        assertTrue("directory" in refused("--store", "  "))
        assertTrue(refused("--model-lane").contains("needs a value"))
        assertEquals(40, refused("--" + "x".repeat(100)).substringAfter("unknown option: ").length, "echoed flag text is bounded")
    }

    @Test
    fun modelLanesNameTheirKeyVariableButCarryNoSecret() {
        val o = parse("--model-lane", "beam,Beam-1.0,https://api.example.test/v1/,BEAM_KEY", "--model-lane", "local,tiny,http://127.0.0.1:8080/v1,LOCAL_KEY")
        assertEquals(ModelLane("beam", "Beam-1.0", "https://api.example.test/v1", "BEAM_KEY"), o.lanes[0], "trailing slash is trimmed")
        assertEquals("http://127.0.0.1:8080/v1", o.lanes[1].baseUrl)
        for (url in listOf("http://localhost", "http://localhost:11434/v1", "http://[::1]:9/x")) {
            assertEquals(url, parse("--model-lane", "p,m,$url,K").lanes.single().baseUrl)
        }
    }

    @Test
    fun laneDestinationsAreHttpsOrLoopbackOnly() {
        for (url in listOf("http://example.com/v1", "http://localhost.evil.com/v1", "http://127.0.0.1.evil.com/v1", "http://localhost@evil.com/", "ftp://localhost/", "https://", "//host", "api.example.test")) {
            assertTrue("https, or http on loopback" in refused("--model-lane", "p,m,$url,K"), url)
        }
    }

    @Test
    fun laneFieldsAreStrictlyShaped() {
        assertTrue("PROVIDER,MODEL,BASE_URL,KEY_ENV" in refused("--model-lane", "p,m,https://x.test"))
        assertTrue("provider" in refused("--model-lane", "Upper,m,https://x.test,K"))
        assertTrue("provider" in refused("--model-lane", "-lead,m,https://x.test,K"))
        assertTrue("KEY_ENV" in refused("--model-lane", "p,m,https://x.test,lowercase"))
        assertTrue("KEY_ENV" in refused("--model-lane", "p,m,https://x.test,HAS SPACE"))
        assertTrue("KEY_ENV" in refused("--model-lane", "p,m,https://x.test,sk-literal-secret"), "a literal key where a variable name belongs is refused")
        assertTrue("configured twice" in refused("--model-lane", "p,m,https://x.test,K", "--model-lane", "q,m,https://y.test,L"))
    }

    @Test
    fun usageStatesTheContractsThatMatter() {
        val usage = McpStdioOptions.USAGE
        for (phrase in listOf("ONLY protocol", "--allow-exec", "--allow-write", "Unregistered tools do not exist", "-32001")) {
            assertTrue(phrase.lowercase() in usage.lowercase() || phrase in usage, "usage lacks '$phrase'")
        }
    }

    @Test
    fun aKuboNodeIsNamedByItsRepositoryAndNeverDiscovered() {
        assertNull(parse().peer, "no flag, no peer")
        val o = parse("--scope", "team/a", "--peer-kubo-repo", "/var/kubo/b", "--peer-kubo-api", "/ip4/127.0.0.1/tcp/5011", "--peer-kubo-bin", "/opt/ipfs", "--peer-timeout-s", "9")
        val peer = o.peer!!
        assertEquals("/var/kubo/b", peer.repo); assertEquals("/ip4/127.0.0.1/tcp/5011", peer.api)
        assertEquals("/opt/ipfs", peer.binary); assertEquals(9, peer.timeoutSeconds)
        assertEquals(30, parse("--scope", "s", "--peer-kubo-repo", "/r").peer!!.timeoutSeconds, "the default is bounded too")
    }

    @Test
    fun peerFlagsAreRefusedWhenTheyCouldWidenAccessByAccident() {
        assertTrue("need --peer-kubo-repo" in refused("--peer-kubo-api", "/ip4/127.0.0.1/tcp/5001"))
        assertTrue("need --peer-kubo-repo" in refused("--peer-timeout-s", "5"))
        assertTrue("need --peer-kubo-repo" in refused("--peer-kubo-bin", "/x/ipfs"))
        assertTrue("at least one --scope" in refused("--peer-kubo-repo", "/r"))
        assertTrue("absolute" in refused("--scope", "s", "--peer-kubo-repo", "relative"))
        assertTrue("loopback" in refused("--scope", "s", "--peer-kubo-repo", "/r", "--peer-kubo-api", "/ip4/10.1.2.3/tcp/5001"))
        assertTrue("1..3600" in refused("--scope", "s", "--peer-kubo-repo", "/r", "--peer-timeout-s", "0"))
        assertTrue("twice" in refused("--peer-kubo-repo", "/a", "--peer-kubo-repo", "/b"))
    }
}
