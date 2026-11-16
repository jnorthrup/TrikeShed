package borg.trikeshed.mlir

import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.KifReview
import borg.trikeshed.kif.KifSourceAssertion
import borg.trikeshed.kif.SumoNixBridge
import borg.trikeshed.lib.*
import kotlin.test.*

class KifToNixTest {
    private fun one(mlir: String): NixText = kifToNix(mlir).also { assertEquals(1, it.size) }[0].b

    /** mlir-nix test/mlir/kif.mlir without its sumo ops: the facts in theory order, then the rule. */
    @Test fun theoryLowersToFactsThenRules() {
        val nix = one("""
            module {
              kif.theory @t {
                %p = kif.atom "p"
                %p2 = kif.atom "p"
                %v = kif.var "?v"
                %l = kif.list(%p, %v)
                %l2 = kif.list(%p2, %v)
                kif.fact %l
                kif.fact %l2
                kif.rule %l implies %l2
              }
            }
        """)
        assertEquals("""{ facts = [ [ "p" "?v" ] [ "p" "?v" ] ]; rules = [ [ "=>" [ "p" "?v" ] [ "p" "?v" ] ] ]; }""", nix)
    }

    /** A graph region: a fact may name a term defined below it, and a list may hold lists. */
    @Test fun termsMayBeDefinedAfterTheirUse() {
        val nix = one("""
            kif.theory @g {
              kif.fact %outer
              %outer = kif.list(%a, %inner)
              %inner = kif.list(%b, %empty)
              %empty = kif.list()
              %a = kif.atom "a"
              %b = kif.atom "b"   // comment
            }
        """)
        assertEquals("""{ facts = [ [ "a" [ "b" [ ] ] ] ]; rules = [ ]; }""", nix)
    }

    @Test fun eachTheoryOfAModuleIsOneExpression() {
        val out = kifToNix("""
            module {
              kif.theory @one attributes {files = ["a.kif"]} { %x = kif.atom "x"  kif.fact %x {file = 0 : i32} }
              kif.theory @two { }
            }
        """)
        assertEquals(_l["one", "two"], out.view.map { it.a })
        assertEquals("""{ facts = [ "x" ]; rules = [ ]; }""", out[0].b)
        assertEquals("""{ facts = [ ]; rules = [ ]; }""", out[1].b)
    }

    /** The bytes the bridge writes (UTF-8 as \hh, quotes, backslashes) come back as the text, escaped for Nix. */
    @Test fun stringsRoundTripThroughMlirAndNixEscapes() {
        val nix = one("""
            kif.theory @s {
              %a = kif.atom "\ce\bb\f0\9f\98\80"
              %q = kif.atom "\22quoted\22 \5cslash \24{x} \0a"
              %t = kif.list(%a, %q)
              kif.fact %t
            }
        """)
        val literal = '"' + """\"quoted\" \\slash \${'$'}{x} \n""" + '"'
        assertEquals("{ facts = [ [ \"λ😀\" $literal ] ]; rules = [ ]; }", nix)
    }

    /** SumoNixBridge's rules are kif ops; the result is the rule its JSON export names. */
    @Test fun lowersWhatSumoNixBridgeEmits() {
        fun rule(id: String, text: String) = KifSourceAssertion(id, ContentId.of("source:$id".encodeToByteArray()), "v1", KifReview.APPROVED, text)
        val export = SumoNixBridge.compile(1, _l[rule("r", "(=> (and (p ?x) (r ?x)) (q ?x))"), rule("s", "(=> (p ?x) (q ?x))")], "(q A)")
        assertEquals("""{ facts = [ ]; rules = [ [ "=>" [ "and" [ "p" "?x" ] [ "r" "?x" ] ] [ "q" "?x" ] ] [ "=>" [ "p" "?x" ] [ "q" "?x" ] ] ]; }""", one(export.mlir))
    }

    @Test fun refusesWhatIsNotKif() {
        val sumo = assertFailsWith<IllegalArgumentException> { kifToNix("kif.theory @t { sumo.subclass \"Human\" \"Hominid\" }") }
        assertEquals("sumo.subclass is not KIF: lower it with sumo-to-kif first", sumo.message)
        assertFailsWith<IllegalArgumentException> { kifToNix("func.func @f { }") }
        assertFailsWith<IllegalArgumentException> { kifToNix("kif.theory @t { %v = kif.var \"x\" }") }
        assertFailsWith<IllegalArgumentException> { kifToNix("kif.theory @t { %v = kif.var \"?\" }") }
    }

    @Test fun refusesBrokenGraphs() {
        assertFailsWith<IllegalStateException> { kifToNix("kif.theory @t { kif.fact %missing }") }
        assertFailsWith<IllegalArgumentException> { kifToNix("kif.theory @t { %a = kif.atom \"a\" %a = kif.atom \"b\" }") }
        assertFailsWith<IllegalArgumentException> { kifToNix("kif.theory @t { %l = kif.list(%l) kif.fact %l }") }
        assertFailsWith<IllegalArgumentException> { kifToNix("kif.theory @t { %a = kif.atom \"a\" ") }
        assertFailsWith<IllegalArgumentException> { kifToNix("kif.theory @t { %a = kif.atom \"a\" } }") }
    }
}
