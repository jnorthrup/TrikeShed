package borg.trikeshed.graal.subvm

import borg.trikeshed.graal.subvm.Teleported.Arr
import borg.trikeshed.graal.subvm.Teleported.Bool
import borg.trikeshed.graal.subvm.Teleported.Bytes
import borg.trikeshed.graal.subvm.Teleported.Num
import borg.trikeshed.graal.subvm.Teleported.Obj
import borg.trikeshed.graal.subvm.Teleported.Opaque
import borg.trikeshed.graal.subvm.Teleported.Real
import borg.trikeshed.graal.subvm.Teleported.Str
import borg.trikeshed.parse.json.JsonSupport
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [Teleported] is the only shape that crosses an isolate wall: canonical bytes, a cid, and a guest projection. */
class TeleportTest {
    private val sample = Obj(linkedMapOf(
        "zeta" to Num(1),
        "alpha" to Arr(listOf(Str("x"), Bool(true), Teleported.Null, Real(2.5))),
        "mid" to Obj(linkedMapOf("b" to Num(2), "a" to Num(1))),
    ))

    @Test fun canonicalSortsObjectKeysAndCarriesNoWhitespace() {
        val c = sample.canonical()
        assertEquals("""{"alpha":["x",true,null,2.5],"mid":{"a":1,"b":2},"zeta":1}""", c)
        assertFalse(c.any { it.isWhitespace() }, c)
        // whitespace inside a string is data, not structure, and is quoted through
        assertEquals("""{"k":"a b\tc\n"}""", Obj(mapOf("k" to Str("a b\tc\n"))).canonical())
    }

    @Test fun cidIsStableAcrossKeyOrderAndSensitiveToValues() {
        val a = Obj(linkedMapOf("b" to Num(1), "a" to Str("s")))
        val b = Obj(linkedMapOf("a" to Str("s"), "b" to Num(1)))
        assertEquals(a.canonical(), b.canonical())
        assertEquals(a.cid, b.cid)
        assertEquals(a.cid.hex.length, 64)
        assertNotEquals(a.cid, Obj(mapOf("a" to Str("s"), "b" to Num(2))).cid)
        // integers and whole reals are different canonical shapes, therefore different cids
        assertEquals("3", Num(3).canonical())
        assertEquals("3.0", Real(3.0).canonical())
        assertNotEquals(Num(3).cid, Real(3.0).cid)
    }

    @Test fun bytesAndOpaqueMarkersRoundTripThroughTheEnvelope() {
        val raw = byteArrayOf(0, 1, 2, -1, 127)
        val bytes = Bytes(raw)
        val opaque = Opaque("fn:Function")
        assertEquals("{\"\$bytes\":\"${java.util.Base64.getEncoder().encodeToString(raw)}\"}", bytes.canonical())
        assertEquals("{\"\$opaque\":\"fn:Function\"}", opaque.canonical())
        // the wire form is the canonical STRING; SubVmProtocol.jsonOf/teleportOf are exact inverses through it
        assertEquals(bytes, SubVmProtocol.teleportOf(SubVmProtocol.jsonOf(bytes)))
        assertEquals(opaque, SubVmProtocol.teleportOf(SubVmProtocol.jsonOf(opaque)))
        assertEquals(bytes, Teleported.parseCanonical(bytes.canonical()))
        assertEquals(opaque, Teleported.parseCanonical(opaque.canonical()))
        // the markers survive nesting inside containers, and opacity propagates upward
        val nested = Obj(mapOf("k" to Arr(listOf(bytes, opaque, Str("s"), Bool(false), Teleported.Null))))
        val back = SubVmProtocol.teleportOf(SubVmProtocol.jsonOf(nested))
        assertEquals(nested, back)
        assertTrue(back.isOpaque)
        assertFalse(Obj(mapOf("k" to Arr(listOf(bytes, Str("s"))))).isOpaque)
        assertEquals(bytes.cid, back.let { (it as Obj).v.getValue("k") }.let { (it as Arr).v.first().cid })
        // a foreign speaker sending the raw JSON object (not the canonical string) is accepted with loss: markers
        // and the Num/Real distinction are not recovered from the generic envelope parser
        val rawJson = SubVmProtocol.teleportOf(JsonSupport.parse(bytes.canonical()))
        assertIs<Obj>(rawJson)
        assertEquals(setOf("\$bytes"), rawJson.v.keys)
        assertEquals(Real(3.0), SubVmProtocol.teleportOf(JsonSupport.parse("3")))
    }

    @Test fun parseCanonicalIsTheExactInverseOfCanonical() {
        val shapes = listOf(
            Teleported.Null, Bool(true), Bool(false), Num(0), Num(-42), Num(Long.MAX_VALUE), Real(0.5), Real(-1.25e10), Real(3.0),
            Str(""), Str("plain"), Str("quote\" back\\ nl\n cr\r tab\t ctl\u0001 uni\u00e9 \u2603"),
            Arr(emptyList()), Obj(emptyMap()),
            Arr(listOf(Num(1), Arr(listOf(Str("x"), Arr(emptyList()))), Obj(mapOf("a" to Teleported.Null)))),
            Obj(linkedMapOf("z" to Num(1), "a" to Real(2.0), "m" to Obj(mapOf("k\"ey" to Str("v"))))),
            Bytes(ByteArray(0)), Bytes(ByteArray(300) { it.toByte() }), Opaque("host:java.lang.Object@1"),
        )
        for (t in shapes) {
            val c = t.canonical()
            assertEquals(t, Teleported.parseCanonical(c), "round trip of $c")
            assertEquals(t.cid, Teleported.parseCanonical(c).cid)
            assertEquals(c, Teleported.parseCanonical(c).canonical(), "canonical is a fixed point")
        }
        // integers and whole reals stay distinct on the wire
        assertEquals(Num(3), Teleported.parseCanonical("3"))
        assertEquals(Real(3.0), Teleported.parseCanonical("3.0"))
        assertEquals(Real(1.0e3), Teleported.parseCanonical("1.0E3"))
        assertEquals(Str("3"), Teleported.parseCanonical("\"3\""))
        // whitespace is tolerated on input, never produced on output
        assertEquals(Obj(mapOf("a" to Arr(listOf(Num(1), Num(2))))), Teleported.parseCanonical(" { \"a\" : [ 1 , 2 ] } "))
        assertFailsWith<IllegalArgumentException> { Teleported.parseCanonical("[1,2") }
        assertFailsWith<IllegalArgumentException> { Teleported.parseCanonical("1 2") }
    }

    @Test fun ofProjectsJsValuesAndToGuestBuildsProxies() {
        Context.newBuilder("js").allowHostAccess(HostAccess.NONE).option("engine.WarnInterpreterOnly", "false").build().use { ctx ->
            assertEquals(Num(42), Teleported.of(ctx.eval("js", "42")))
            assertEquals(Real(1.5), Teleported.of(ctx.eval("js", "1.5")))
            assertEquals(Str("hi"), Teleported.of(ctx.eval("js", "'hi'")))
            assertEquals(Bool(true), Teleported.of(ctx.eval("js", "true")))
            assertEquals(Teleported.Null, Teleported.of(ctx.eval("js", "null")))
            assertEquals(Teleported.Null, Teleported.of(ctx.eval("js", "undefined")))
            assertEquals(Teleported.Null, Teleported.of(null))
            assertEquals(Arr(listOf(Num(1), Str("a"), Bool(false))), Teleported.of(ctx.eval("js", "[1,'a',false]")))

            val obj = Teleported.of(ctx.eval("js", "({b:2, a:'x', c:[1]})"))
            assertEquals(Obj(mapOf("a" to Str("x"), "b" to Num(2), "c" to Arr(listOf(Num(1))))), obj)
            assertFalse(obj.isOpaque)

            val fn = Teleported.of(ctx.eval("js", "(function f(x){return x})"))
            assertIs<Opaque>(fn)
            assertTrue(fn.isOpaque)
            assertTrue(fn.repr.startsWith("fn:"), fn.repr)
            assertTrue(Arr(listOf(Num(1), fn)).isOpaque)
            assertTrue(Obj(mapOf("f" to fn)).isOpaque)
            assertTrue(Teleported.of(ctx.eval("js", "({f: function(){}})")).isOpaque)

            // host → guest: containers become polyglot proxies, primitives stay primitives
            assertIs<ProxyArray>(Arr(listOf(Num(1))).toGuest())
            assertIs<ProxyObject>(Obj(mapOf("a" to Num(1))).toGuest())
            assertIs<ProxyArray>(Bytes(byteArrayOf(1, 2)).toGuest())
            assertEquals(7L, Num(7).toGuest())
            assertEquals("s", Str("s").toGuest())
            assertNull(Teleported.Null.toGuest())

            // and those proxies are usable inside the guest under HostAccess.NONE
            ctx.getBindings("js").putMember("o", Obj(mapOf("a" to Num(1), "b" to Num(2), "xs" to Arr(listOf(Num(10), Num(20))))).toGuest())
            assertEquals(Num(3), Teleported.of(ctx.eval("js", "o.a + o.b")))
            assertEquals(Num(2), Teleported.of(ctx.eval("js", "o.xs.length")))
            assertEquals(Num(30), Teleported.of(ctx.eval("js", "o.xs[0] + o.xs[1]")))
        }
    }

    @Test fun ofHostProjectsJavaValues() {
        assertEquals(Num(5), Teleported.ofHost(5))
        assertEquals(Num(5), Teleported.ofHost(5L))
        assertEquals(Real(0.5), Teleported.ofHost(0.5))
        assertEquals(Str("s"), Teleported.ofHost("s"))
        assertEquals(Bytes(byteArrayOf(1)), Teleported.ofHost(byteArrayOf(1)))
        assertEquals(Arr(listOf(Num(1), Str("a"))), Teleported.ofHost(listOf(1, "a")))
        assertEquals(Obj(mapOf("a" to Num(1))), Teleported.ofHost(mapOf("a" to 1)))
        assertEquals(Num(9), Teleported.ofHost(Num(9)))
        assertIs<Opaque>(Teleported.ofHost(Any()))
    }
}
