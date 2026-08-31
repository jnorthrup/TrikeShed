package modelmux

import modelmux.acp.AcpCodec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The content-parse gate.
 *
 * The defect these exist to prevent is not "returns empty" — it is
 * FABRICATION. `"content":null` used to make the scanner walk to the next quoted
 * token in the document and return it, so a real openrouter reply came back as
 * the answer `refusal` (the name of the following key). Nothing downstream can
 * distinguish an invented answer from a real one, which makes this the worst
 * failure mode in the whole chat path.
 */
class AcpContentParseTest {

    private fun content(raw: String): String = AcpCodec.parseResponse(raw).a

    @Test
    fun `ordinary openai shaped reply`() {
        assertEquals("ok", content(
            """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""))
    }

    @Test
    fun `null content does not return the next key name`() {
        // The live regression, verbatim in shape.
        assertEquals("", content(
            """{"choices":[{"message":{"role":"assistant","content":null,"refusal":null}}]}"""))
    }

    @Test
    fun `null content followed by a populated field is still empty`() {
        assertEquals("", content(
            """{"choices":[{"message":{"content":null,"reasoning":"thinking out loud"}}]}"""))
    }

    @Test
    fun `empty string content falls through to a later populated content`() {
        assertEquals("real answer", content(
            """{"choices":[{"message":{"content":""}},{"message":{"content":"real answer"}}]}"""))
    }

    @Test
    fun `anthropic style content blocks are read from text`() {
        assertEquals("hello there", content(
            """{"content":[{"type":"text","text":"hello there"}],"role":"assistant"}"""))
    }

    @Test
    fun `escapes are decoded, not mangled`() {
        assertEquals("line one\nline two\ttabbed",
            content("""{"choices":[{"message":{"content":"line one\nline two\ttabbed"}}]}"""))
    }

    @Test
    fun `a quoted content containing the word refusal is returned intact`() {
        // Guard against over-correcting: "refusal" is a legal answer when the
        // model actually said it.
        assertEquals("refusal", content(
            """{"choices":[{"message":{"content":"refusal"}}]}"""))
    }

    @Test
    fun `no content key at all yields empty`() {
        assertEquals("", content("""{"error":{"message":"boom"}}"""))
    }

    @Test
    fun `usage still parses alongside a null content`() {
        val r = AcpCodec.parseResponse(
            """{"choices":[{"message":{"content":null}}],"usage":{"prompt_tokens":23,"completion_tokens":17}}""")
        assertEquals("", r.a)
        assertEquals(23, r.b.a)
        // The pairing that makes the LCNC diagnostic possible: billed output
        // tokens with no text means the text is somewhere the parser did not look.
        assertEquals(17, r.b.b)
    }
}
