package borg.trikeshed.parse

import borg.trikeshed.common.collections._l
import borg.trikeshed.lib.*
import borg.trikeshed.parse.JsonParser.index
import borg.trikeshed.parse.JsonParser.jsPath
import kotlin.test.*

class JsonPathTest {
    @Test
    fun `test depth correctness and robustness with empty arrays`() {
        var json = "[0,[],[1],[[[ ]]]]"
        var d = mutableListOf<Int>()
        var elem = index(json.toSeries(), d)
        assertEquals(0, d[0])
        assertEquals(1, d[1])
        assertEquals(1, d[2])
        assertEquals(3, d[3])


        json = """[0, {}, [1], {"1": {"2": {"3": {"4": 1}}}}]"""
        d = mutableListOf<Int>()
        elem = index(json.toSeries(), d)
        assertEquals(0, d[0])
        assertEquals(1, d[1])
        assertEquals(1, d[2])
        assertEquals(4, d[3])

        json = """ [ 0,1 , 2 ,   3]"""
        d = mutableListOf<Int>()
        elem = index(json.toSeries(), d)
        assertEquals(0, d[0])
        assertEquals(0, d[1])
        assertEquals(0, d[2])
        assertEquals(0, d[3])
        json = """ [ 0,1 , 2 ,   [ [[ [3]] ]] ] """
        d = mutableListOf<Int>()
        elem = index(json.toSeries(), d)
        assertEquals(0, d[0])
        assertEquals(0, d[1])
        assertEquals(0, d[2])
        assertEquals(4, d[3])
    }

    @Test
    fun `test the simplest path and some misc whitespace`() {
        run {
            val src = ("""[0,1,2,3]""").toSeries()
            val depths = mutableListOf<Int>()
            val element = index(src, depths)
            val path: JsPath = _l[0].toJsPath
            val expected = 0.0
            val context = element j src
            val result = jsPath(context, path, true, depths)
            assertEquals(expected, result)
        }
        run {
            val src = (""" [0 , 1,2 , 3 ] """).toSeries()
            val depths = mutableListOf<Int>()
            val element = index(src, depths)
            val path: JsPath = _l[0].toJsPath
            val result = jsPath(element j src, path, true, depths)
            val expected = 0.0
            assertEquals(expected, result)
        }
    }
    @Test
    fun test0() {
        val src = ("""[0]""").toSeries()
        val element = index(src)
        val path: JsPath = _l[0].toJsPath
        val expected = 0.0
        val result = jsPath(element j src, path, true, mutableListOf())
        assertEquals(expected, result)

    }
    @Test fun test00() { val src = ("""{"0":0}""").toSeries()
        val result = jsPath(index(src) j src, _l[0].toJsPath, true, mutableListOf())
        assertEquals(0.0, result) }

    @Test
    fun test1() {
        val src = ("""{"a":{"b":[1,2,3,4,5],"c":"hi","d":true},"e":false}""").toSeries()
        val element = index(src)
        val path: JsPath = _l["a", "b", 2].toJsPath
        val result = jsPath(element j src, path, true, mutableListOf())
        val expected = 3.0
        assertEquals(expected, result)

    }

    //tests the use of index on jsObj
    @Test
    fun test2() {
        val src = ("""{"a":{"b":[1,2,3,4,{"meh":[4,3,2,1]}],"c":"hi","d":true},"e":false}""").toSeries()
        val element = index(src)
        val path: JsPath = _l[("a"), ("b"), (4), 0, (1)].toJsPath
        val result = jsPath(element j src, path, true, mutableListOf())
        val expected = 3.0
        assertEquals(expected, result)
    }
    @Test
    fun `handle empty jsarray`() {
        val src = ("""[0,[],[1],[[[ ]]]] """).toSeries()
        val element = index(src)
        val path: JsPath = _l[3,0,0].toJsPath
        val result = jsPath(element j src, path, true, mutableListOf())
        if (result is Join<*,*>) {
            val r = result as Join<*,*>
            if(r.a!=0) fail("result.a is not an empty Series")
        }
    }

    @Test
    fun `handle empty jsObject`() {
        val src = ("""[0,[],[1],[[{ }]]] """).toSeries()
        val element = index(src)
        val path: JsPath = _l[3,0,0].toJsPath
        val result = jsPath(element j src, path, true, mutableListOf())
        if (result is Map<*,*>) {
            val r = result as Map<*,*>
            if(r.isNotEmpty()) fail("result is not an empty Map")
        }
    }

}
