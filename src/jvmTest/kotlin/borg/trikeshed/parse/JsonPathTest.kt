package borg.trikeshed.parse

import borg.trikeshed.common.collections._l
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.json.JsPath
import borg.trikeshed.parse.json.JsonParser.index
import borg.trikeshed.parse.json.JsonParser.jsPath
import borg.trikeshed.parse.json.toJsPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

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
        d = mutableListOf()
        elem = index(json.toSeries(), d)
        assertEquals(0, d[0])
        assertEquals(1, d[1])
        assertEquals(1, d[2])
        assertEquals(4, d[3])
        json = """[0, { } , [ 1
            |], {"1"
            |: {"2": { "3 ": { "4" : 1} }} } ] 
            |""".trimMargin()
        d = mutableListOf()
        elem = index(json.toSeries(), d)
        assertEquals(0, d[0])
        assertEquals(1, d[1])
        assertEquals(1, d[2])
        assertEquals(4, d[3])

        json = """ [ 0,1 , 2 ,   3]"""
        d = mutableListOf()
        elem = index(json.toSeries(), d)
        assertEquals(0, d[0])
        assertEquals(0, d[1])
        assertEquals(0, d[2])
        assertEquals(0, d[3])
        json = """ [ 0,1 , 2 ,   [ [[ [3]] ]] ] """
        d = mutableListOf()
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
            val r = result
            if(r.a!=0) fail("result.a is not an empty Series")
        }
    }

    @Test
    fun `handle empty jsObject`() {
        val src = ("""[0,[],[1],[[{ }]]] """).toSeries()
        val element = index(src)
        val path: JsPath = _l[3, 0, 0].toJsPath
        val result = jsPath(element j src, path, true, mutableListOf())
        if (result is Map<*, *>) {
            val r = result
            if (r.isNotEmpty()) fail("result is not an empty Map")
        }
    }

    val systemJson =
        """       {"id64":2064711,"name":"Ooscs Chreou AA-A h0","coords":{"x":-10149.875,"y":830.28125,"z":27589.65625},"allegiance":null,"government":"None","primaryEconomy":"None","secondaryEconomy":"None","security":"Anarchy","population":0,"bodyCount":1,"date":"2023-01-31 01:21:12+00","bodies":[{"id64":2064711,"bodyId":0,"name":"Ooscs Chreou AA-A h0","type":"Star","subType":"Black Hole","distanceToArrival":0.0,"mainStar":true,"age":2,"spectralClass":null,"luminosity":"VII","absoluteMagnitude":20.0,"solarMasses":13.6875,"solarRadius":0.0000580558894234364,"surfaceTemperature":0.0,"rotationalPeriod":0.00000144196759259259,"axialTilt":0.0,"stations":[],"updateTime":"2023-01-31 01:21:02+00"}],"stations":[]} """

    @Test
    fun `test body 0 name`() {

        val src = systemJson.toSeries()
        val element = index(src)
        val path: JsPath = _l["bodies", 0, "name"].toJsPath
        val result = jsPath(element j src, path, true, mutableListOf())
        val expected = "Ooscs Chreou AA-A h0"
        assertEquals(expected, result)
//        expected:<[Ooscs Chreou AA-A h0]> but was:<[name]>
//what's the bug?
    }


}
