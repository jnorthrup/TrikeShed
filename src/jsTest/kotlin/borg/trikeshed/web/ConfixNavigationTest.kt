package borg.trikeshed.web

import org.w3c.dom.url.URLSearchParams
import borg.trikeshed.parse.confix.Syntax
import kotlin.test.*

class ConfixNavigationTest {
    val cid = "sha256:" + "a".repeat(64)

    @Test fun canonicalCborLinksRetainAnExplicitSyntaxAndRejectAmbiguousSelections() {
        val href = ConfixNavigation.href(cid, "[\"receipt\"]", Syntax.CBOR)
        val (content, path, syntax) = ConfixNavigation.source(URLSearchParams(href.substringAfter('?')))
        assertEquals(cid, content)
        assertContentEquals(arrayOf<Any>("receipt"), path)
        assertEquals(Syntax.CBOR, syntax)
        assertEquals(Syntax.JSON, ConfixNavigation.source(URLSearchParams("content=$cid")).third)
        for (raw in listOf("syntax=CBOR", "content=$cid&syntax=", "content=$cid&syntax=YAML",
            "content=$cid&syntax=CBOR&syntax=JSON", "content=$cid&syntax=CBOR&load=preset")) {
            val query = URLSearchParams(raw)
            assertTrue(ConfixNavigation.requested(query))
            assertFails { ConfixNavigation.source(query) }
        }
        assertFails { ConfixNavigation.href(cid, syntax = Syntax.YAML) }
    }

    @Test fun linksPreserveKeysAndOrdinalsWithoutLoadingAProgram() {
        val path = "[\"lcncRun\",\"outputs\",\"fn.雪&+/#\\\"\",0,\"\"]"
        val href = ConfixNavigation.href(cid, path)
        assertTrue(href.startsWith("/panels?content="))
        val query = URLSearchParams(href.substringAfter('?'))
        assertFalse(query.has("load"))
        val (source, steps) = ConfixNavigation.source(query)
        assertEquals(cid, source)
        assertContentEquals(arrayOf<Any>("lcncRun", "outputs", "fn.雪&+/#\"", 0, ""), steps)
        assertEquals(path, JSON.stringify(steps))
        assertContentEquals(emptyArray(), ConfixNavigation.source(URLSearchParams("content=$cid")).second)
    }

    @Test fun invalidOrAmbiguousSourcesRemainInspectionRequests() {
        for (raw in listOf("content=", "content=latest", "path=[]", "content=$cid&content=$cid",
            "content=$cid&path=[]&path=[]", "content=$cid&load=preset", "content=$cid&documentTask=task",
            "content=$cid&debug=token", "content=$cid&run=id")) {
            val query = URLSearchParams(raw)
            assertTrue(ConfixNavigation.requested(query), raw)
            assertFails { ConfixNavigation.source(query) }
        }
        assertFalse(ConfixNavigation.requested(URLSearchParams("load=preset")))
        assertFailsWith<IllegalArgumentException> { ConfixNavigation.href("javascript:alert(1)") }
    }

    @Test fun invalidAndUnboundedPathsCannotBeCoercedIntoOtherSelections() {
        for (path in listOf("null", "{}", "[true]", "[null]", "[{}]", "[[]]", "[-1]", "[0.5]",
            "[2147483648]", "[1e400]", "[", "[" + List(33) { "0" }.joinToString(",") + "]",
            "[\"" + "x".repeat(8192) + "\"]")) {
            assertFails(path) { ConfixNavigation.href(cid, path) }
        }
    }
}
