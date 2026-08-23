package borg.trikeshed.vm

import borg.trikeshed.vm.Teleported.Arr
import borg.trikeshed.vm.Teleported.Bool
import borg.trikeshed.vm.Teleported.Bytes
import borg.trikeshed.vm.Teleported.Num
import borg.trikeshed.vm.Teleported.Obj
import borg.trikeshed.vm.Teleported.Opaque
import borg.trikeshed.vm.Teleported.Real
import borg.trikeshed.vm.Teleported.Str
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The portable half of the ABI: canonical form is exact, sorted, and round-trips on every target. */
class TeleportedCanonicalTest {
    private val sample = Obj(mapOf(
        "z" to Arr(listOf(Num(1), Real(2.5), Real(3.0), Str("a\"b\n"), Bool(false), Teleported.Null)),
        "a" to Bytes(byteArrayOf(1, 2, 3)),
        "m" to Opaque("fn:f"),
    ))

    @Test
    fun canonicalIsSortedCompactAndMarked() {
        val c = sample.canonical()
        assertEquals("""{"a":{"${'$'}bytes":"AQID"},"m":{"${'$'}opaque":"fn:f"},"z":[1,2.5,3.0,"a\"b\n",false,null]}""", c)
        assertEquals("\"\\u0001\"", Str("\u0001").canonical())
        assertTrue(c.indexOf("\"a\"") < c.indexOf("\"m\"") && c.indexOf("\"m\"") < c.indexOf("\"z\""))
    }

    @Test
    fun parseCanonicalIsTheExactInverse() {
        assertEquals(sample, Teleported.parseCanonical(sample.canonical()))
        assertEquals(Num(3), Teleported.parseCanonical("3"))
        assertEquals(Real(3.0), Teleported.parseCanonical("3.0"))
        assertEquals(Real(1e3), Teleported.parseCanonical("1e3"))
        assertEquals(sample.cid, Teleported.parseCanonical(sample.canonical()).cid)
    }

    @Test
    fun malformedInputIsRejected() {
        assertFailsWith<IllegalArgumentException> { Teleported.parseCanonical("[1,") }
        assertFailsWith<IllegalArgumentException> { Teleported.parseCanonical("{\"a\":1} x") }
    }

    @Test
    fun hostProjectionCoversTheCommonSubset() {
        assertEquals(Num(5), Teleported.ofHost(5))
        assertEquals(Real(0.5), Teleported.ofHost(0.5))
        assertEquals(Arr(listOf(Num(1), Str("a"))), Teleported.ofHost(listOf(1, "a")))
        assertEquals(Obj(mapOf("a" to Num(1))), Teleported.ofHost(mapOf("a" to 1)))
        assertTrue(Teleported.ofHost(Any()).isOpaque)
        assertEquals(Obj(mapOf("id" to Num(7), "op" to Str("eval"))), Teleported.obj("id" to 7, "op" to "eval"))
    }

    @Test
    fun envelopeHelpersReadFields() {
        val env = Teleported.obj("id" to 9, "ok" to true, "value" to Num(4), "op" to "eval")
        assertEquals(9, env.int("id")); assertEquals(true, env.bool("ok")); assertEquals("eval", env.str("op")); assertEquals(Num(4), env.field("value"))
        assertEquals(env, SubVmProtocol.decode(SubVmProtocol.encode(env)))
        assertEquals(Num(4), SubVmProtocol.teleportOf("4"))
        assertEquals(Str("not json"), SubVmProtocol.teleportOf("not json"))
    }
}
