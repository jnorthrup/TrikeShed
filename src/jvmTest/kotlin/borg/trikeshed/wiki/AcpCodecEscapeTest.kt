package borg.trikeshed.wiki

import borg.trikeshed.lib.j
import borg.trikeshed.lib.emptySeriesOf
import modelmux.acp.AcpCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The model lane's escape handling. This is not a wiki concern by nature —
 * it is here because the WikiSkill passes are what exposed it: a Maintainer's
 * markdown pattern page arrives as a JSON string, and `AcpCodec.extractQuoted`
 * used to skip the backslash and take the next character literally, turning
 * every `\n` into the letter `n`. The whole response came back as one line and
 * the edit script would not parse.
 */
class AcpCodecEscapeTest {

    @Test
    fun responseContentDecodesJsonEscapes() {
        val wire = """{"choices":[{"message":{"role":"assistant","content":""" +
            "\"line1\\nline2\\ttabbed\\\"quoted\\\" back\\\\slash \\u00e9\"" +
            """}}],"usage":{"prompt_tokens":11,"completion_tokens":22}}"""
        val (content, usage) = AcpCodec.parseResponse(wire)
        assertEquals("line1\nline2\ttabbed\"quoted\" back\\slash \u00e9", content)
        assertEquals(11, usage.a)
        assertEquals(22, usage.b)
    }

    @Test
    fun aMultiLineMarkdownAnswerSurvivesTheCodec() {
        val answer = "# Pattern\n\n- root cause\n- workaround\n\nTraces: sha256:abc\n"
        val wire = """{"choices":[{"message":{"content":"""" +
            answer.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") +
            """"}}]}"""
        assertEquals(answer, AcpCodec.parseResponse(wire).a)
        assertEquals(6, AcpCodec.parseResponse(wire).a.count { it == '\n' })
    }

    @Test
    fun requestEncodingEscapesEveryControlCharacter() {
        val msgs = listOf("user" to "tab\there\r\nand a \u0007 bell")
        val headers: borg.trikeshed.lib.Series<modelmux.acp.AcpMessage> = emptySeriesOf()
        val tools: borg.trikeshed.lib.Series<modelmux.acp.AcpTool> = emptySeriesOf()
        val meta: modelmux.acp.AcpMeta = "glm-4.7-flash" j ("chat" j headers)
        val body: modelmux.acp.AcpRequestBody =
            (msgs.size j { i: Int -> msgs[i].first j msgs[i].second }) j tools
        val json = AcpCodec.encodeRequest(meta j body, maxTokens = 8, temperature = 0.0)
        assertTrue("\\t" in json && "\\r" in json && "\\n" in json && "\\u0007" in json, json)
        // No raw control character rides inside the body.
        assertTrue(json.none { it.code < 0x20 }, "raw control character in the encoded request")
        // …and it round-trips through a strict JSON reader.
        val parsed = borg.trikeshed.parse.json.JsonSupport.parse(json) as Map<*, *>
        val content = ((parsed["messages"] as List<*>)[0] as Map<*, *>)["content"]
        assertEquals("tab\there\r\nand a \u0007 bell", content)
    }
}
