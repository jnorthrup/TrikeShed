package borg.trikeshed.parse.confix

import borg.trikeshed.parse.confix.Path
import borg.trikeshed.parse.confix.Reify
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.asSeries
import borg.trikeshed.parse.confix.contextOf
import borg.trikeshed.parse.confix.path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ConfixJsonTest {
    @Test
    fun jsonStringUnescape_red() {
        // JSON contains literal escape sequences (backslash+n and backslash+uXXXX)
        val json = """{"s":"line1\nline2","u":"\u0041"}"""
        val ctx = contextOf(Syntax.JSON, json.asSeries())

        val resS = Path.resolve(ctx, path("s"))
        assertNotNull(resS, "path s resolved")
        val vS = Reify.reify(resS)
        // Expecting decoded newline; current Confix implementation returns raw escapes -> RED
        assertEquals("line1\nline2", vS, "JSON string should unescape \\n to newline")

        val resU = Path.resolve(ctx, path("u"))
        assertNotNull(resU)
        val vU = Reify.reify(resU)
        // Expecting Unicode escape to be decoded to 'A'
        assertEquals("A", vU, "JSON string should decode \\uXXXX to corresponding codepoint")
    }
}
