package borg.trikeshed.userspace.nio.charset

import borg.trikeshed.userspace.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class CharsetTest {
    @Test
    fun utf8RoundTrip() {
        val charset = Charset.forName("utf-8")
        val original = "hello, world"
        val decoded = charset.decode(charset.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun iso88591ReplacesNonLatin1WithQuestionMark() {
        val charset = Charset.forName("ISO-8859-1")
        val original = "a\u00f1😊c"
        val actual = charset.decode(charset.encode(original))

        assertEquals("a\u00f1?c", actual)
    }
}
