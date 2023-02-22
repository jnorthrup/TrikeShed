package borg.trikeshed.parse

import borg.trikeshed.common.parser.simple.CharSeries
import borg.trikeshed.lib.*
import borg.trikeshed.parse.JsonParser.SplittableObject
import kotlinx.coroutines.handleCoroutineException
import kotlin.test.Test

//unit tests
class TestJson {
    @Test
    fun test() {
        val json = """
            {
                "name": "John Doe",
                "age": 43,
                "phones": [
                    "+44 1234567",
                    "+44 2345678"
                ],
                "address": {
                    "street": "Downing Street",
                    "city": "London"
                }
            }
        """.trimIndent()
        val src = CharSeries(json)
        //test that lengths are the same between source handleCoroutineException() series limit
        assert(src.size == json.length)
        assert(src.limit== json.length )

        val rawIndex: JsonParser.SplittableScope<*> = JsonParser.open(src)

        val index: SplittableObject? = rawIndex as? SplittableObject
        val entries: Series<Int>? = index?.entries

        val pair = index!![0]
        val key = pair.a
        val value = pair.b

        val keyString = key.asString()
        val valueString: JsonParser.IndexString? = value as? JsonParser.IndexString
        val reify1 = valueString?.reify

        val reify = rawIndex.reify as? Map<String, Any?>

        debug {
            debug { }
        }
    }
}