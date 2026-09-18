package borg.trikeshed.kif

import borg.trikeshed.lib.s_
import borg.trikeshed.lib.view
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KifStrictParsingTest {
    @Test
    fun truncatedFormsCannotConsumeTheNextFile() {
        for (text in s_[
            "(subclass Child Parent",
            "(subclass Child Parent))",
            "(documentation Child EnglishLanguage \"unterminated)",
            "(documentation Child EnglishLanguage \"escaped\\\"",
            "'",
            ") (subclass Child Parent)",
        ].view) assertFailsWith<IllegalArgumentException>(text) { KifExpr.parseAll(text, strict = true) }
    }

    @Test
    fun commentsStringsAndQuotationKeepFormBoundaries() {
        val text = "; (comment)\n(documentation Child EnglishLanguage \"(; \\\"text\\\")\")\n'(subclass Child Parent)\n(instance Item Child)"
        val forms = KifExpr.parseAll(text, strict = true)
        assertEquals(3, forms.size)
        assertEquals(KifExpr.parseAll(text), forms)
        assertEquals(KifExpr.Quoted(KifExpr.parse("(subclass Child Parent)")), forms[1])
        assertEquals(KifExpr.parse("(instance Item Child)"), forms[2])
    }
}
