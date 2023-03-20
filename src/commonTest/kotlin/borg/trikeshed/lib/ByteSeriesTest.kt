package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertEquals

class ByteSeriesTest {

    @Test
    fun `seekTo leaves the token within limit when found`() {
        val bs = ByteSeries("a\nbc\ncde\n")
        bs.pos(0)
        bs.seekTo('\n'.code.toByte())
        assert(bs.get != '\n'.code.toByte())
    }

    @Test
    fun `ByteSeries-slice-toArray returns the slice not the root`() {
        val foo = ByteSeries("123456778990")
        val slice = foo.pos(5).slice

        val array = slice.toArray()
        assert(array.size == foo.size - 5)
    }

    @Test
    fun `ByteSeries-flip-toArray size is on limit and not size`() {
        val foo = ByteSeries("123456778990")
        var ffff = foo.pos(5).flip()

        val array = ffff.toArray()
        assertEquals(5, array.size)
    }
}