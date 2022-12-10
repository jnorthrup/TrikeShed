package testingstuff

import borg.trikeshed.isam.meta.PlatformCodec
import kotlin.test.Test


class TestPlatformCodec {
    @Test
    fun testPlatformCodec() {
        val r=PlatformCodec. Companion.currentPlatformCodec
        println(r)
    }
}