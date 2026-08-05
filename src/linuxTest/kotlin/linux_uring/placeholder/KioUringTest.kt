package linux_uring.placeholder

import kotlinx.cinterop.*
import linux_uring.include.*
import platform.posix.*
import kotlin.test.*

class KioUringTest {
    @Test
    fun testSqeSubmit() {
        memScoped {
            val s = KioUring()
            val triple = with(s) { sqePreamble() }
            s.sqeSubmit(triple)
            assertEquals(triple.third, s.sqRing.array[triple.third.toInt()])
            assertEquals(triple.second, s.sqRing.tail.pointed.value)
        }
    }
}
