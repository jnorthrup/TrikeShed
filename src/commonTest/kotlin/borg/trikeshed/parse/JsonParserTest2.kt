package borg.trikeshed.parse

import borg.trikeshed.common.collections.s_
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class JsonParserTest2 {

    val jsonString: String = """
            {
              "string": "Hello, world!",
              "number": 42.0,
              "bool": true,
              "null": null,
              "array": [1, 2, 3],
              "object": {"key": "value"}
            }
        """.trimIndent()

    val expectedJsonElement: JsElement = Twin(0, jsonString.length - 1) j s_[29, 47, 63, 79, 101]

    @Test
    fun `test scanJson`() {
        val json = CharSeries(jsonString)
        val (a1, b1) = expectedJsonElement
        val index = JsonParser.index(json)
        val (a2, b2) = index
        assertEquals(a1.pair.toString(), a2.pair.toString())
        val expected = b1.toList().toString()
        val actual = b2.toList().toString()
        assertEquals(expected, actual)
    }

    val expectedParsedJson: LinkedHashMap<String, Any?> = linkedMapOf(
        "string" to "Hello, world!",
        "number" to 42.0,
        "bool" to true,
        "null" to null,
        "array" to listOf(1, 2, 3),
        "object" to mapOf("key" to "value")
    )

    /** tests each of the above members */
    @Test
    fun reifyTest() {
        val reifiedObj = JsonParser.reify(jsonString.toSeries()) as Map<String, Any?>

        reifiedObj["string"]?.let {
            assertEquals("Hello, world!", it)
        } ?: TODO("finish Test")
        reifiedObj["number"]?.let { assertEquals(42.0, it) } ?: TODO("finish Test")
        reifiedObj["bool"]?.let { assertEquals(true, it) } ?: TODO("finish Test")
        reifiedObj["null"]?.let { fail() }
        reifiedObj["array"]?.let {
            logDebug { "it=$it" }
            val list = it as List<*>
            debugging
            /*  val anies = it as Series<Any?>
              assertEquals(listOf(1.0, 2.0, 3.0).toString(), anies.toList().toString())*/
        } ?: TODO("finish Test")
        reifiedObj["object"]?.let { assertEquals(mapOf("key" to "value"), it) } ?: TODO("finish Test")
    }
}