package borg.trikeshed.sctp

import borg.trikeshed.lib.j
import kotlin.test.Test
import kotlin.test.fail

class LiburingFacadeSeamTest {
    @Test
    fun testJvmLiburingFacadeSeamFails() {
        val seam = JvmLiburingFacadeSeam()
        seam.submitBatch(0 j { byteArrayOf() })

    }
}
