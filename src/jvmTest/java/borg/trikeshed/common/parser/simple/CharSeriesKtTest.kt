package borg.trikeshed.common.parser.simple

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import  kotlin.test.*


class CharSeriesKtTest {

    @Test
    fun testDivString() {
        val cs = CharSeries("a b c d e f g h i j k l m n o p q r s t u v w x y z")
        val res = cs / " "

        assert(res.size == 26)

        (CharSeries("banana") / "na").let {
            assert(it.size == 2)
            assert(it[0].asString() == "ba")
            assert(it[1].asString() == "na")
        }
    }
}