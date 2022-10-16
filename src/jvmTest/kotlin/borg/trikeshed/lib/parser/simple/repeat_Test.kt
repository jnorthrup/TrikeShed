package borg.trikeshed.lib.parser.simple

import kotlin.test.*

class repeat_Test {

    @Test
    operator fun invoke() {
        val should_work = CharSeries("abcabcabcabcabc")
        val should_fail = CharSeries("abcabcabcabcab")

        val string_ = string_("abc")
        val r3 = string_[3]

        val r31 = r3(should_work.clone())
        assertEquals(r31, should_work.clone().pos(9))

        val r5 = string_[5]
        val r51 = r5(should_fail.clone())
        assertEquals(null, r51)


        repeat(0){};

    }
}

