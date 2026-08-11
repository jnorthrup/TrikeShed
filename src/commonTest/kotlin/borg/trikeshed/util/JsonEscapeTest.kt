package borg.trikeshed.util

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonEscapeTest {

    @Test
    fun plain_string_round_trips() {
        assertEquals("hello", jsonUnescape("hello"))
    }

    @Test
    fun does_not_strip_outer_quotes() {
        // Canonical behavior: callers strip quotes themselves.
        assertEquals("\"quoted\"", jsonUnescape("\"quoted\""))
    }

    @Test
    fun basic_two_char_escapes() {
        assertEquals("a\nb", jsonUnescape("a\\nb"))
        assertEquals("a\tb", jsonUnescape("a\\tb"))
        assertEquals("a\rb", jsonUnescape("a\\rb"))
        assertEquals("a\u0008b", jsonUnescape("a\\bb"))
        assertEquals("a\u000Cb", jsonUnescape("a\\fb"))
        assertEquals("a\"b", jsonUnescape("a\\\"b"))
        assertEquals("a\\b", jsonUnescape("a\\\\b"))
        assertEquals("a/b", jsonUnescape("a\\/b"))
    }

    @Test
    fun unicode_escape_four_hex_digits() {
        assertEquals("A", jsonUnescape("\\u0041"))
        assertEquals("AB", jsonUnescape("\\u0041\\u0042"))
    }

    @Test
    fun truncated_unicode_escape_passes_backslash_and_u_literally() {
        // Lenient: only 2 chars after \u instead of 4.
        assertEquals("\\uab", jsonUnescape("\\uab"))
    }

    @Test
    fun non_hex_unicode_escape_passes_backslash_and_u_literally() {
        assertEquals("\\uzzzz", jsonUnescape("\\uzzzz"))
    }

    @Test
    fun trailing_backslash_is_emitted_literally() {
        assertEquals("a\\", jsonUnescape("a\\"))
    }

    @Test
    fun unknown_escape_passes_both_chars_through() {
        assertEquals("a\\zb", jsonUnescape("a\\zb"))
    }

    @Test
    fun string_without_backslash_is_unchanged() {
        assertEquals("no escapes here", jsonUnescape("no escapes here"))
    }

    @Test
    fun empty_string_round_trips() {
        assertEquals("", jsonUnescape(""))
    }
}
