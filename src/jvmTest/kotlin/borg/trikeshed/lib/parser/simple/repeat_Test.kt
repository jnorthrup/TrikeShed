package borg.trikeshed.lib.parser.simple

import kotlin.test.*

class repeat_Test {

    @Test
    operator fun invoke() {
        val should_work = CharSeries("abcabcabcabcabc")
        val should_fail = CharSeries("abcabcabcabcab")

        val tstr = string_("abc")

        val baseline= tstr ( CharSeries("abc" ))
        println(should_work.clone())
        println(baseline)


        val r3 = tstr[3]

        val r31 = r3(should_work.clone())
        assertEquals(should_work.clone().pos(9), r31)

        val r5 = tstr[5]
        val r51 = r5(should_fail.clone())
        assertEquals(null, r51)


        repeat(0){};

    }
}

