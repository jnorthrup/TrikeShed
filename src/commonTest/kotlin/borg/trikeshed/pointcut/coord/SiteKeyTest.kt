package borg.trikeshed.pointcut.coord

import kotlin.test.Test
import kotlin.test.assertEquals
import borg.trikeshed.classfile.model.BytecodePointcutKind

class SiteKeyTest {
    @Test
    fun testConfixPath() {
        val jvmSite = SiteKey("com/example/MyClass", "myMethod", "()V", 42, 10, 5)
        assertEquals("/classes/com/example/MyClass/myMethod/()V/42", jvmSite.confixPath)

        val guestSite = SiteKey("python", "myscript.py", "", -1, 15, 0)
        assertEquals("guest/python/myscript.py#15:0", guestSite.confixPath)
    }

    @Test
    fun testPointcutKinds() {
        assertEquals(BytecodePointcutKind.LOCAL_READ, PointcutKinds.kindOf(0xA5.toByte()))
        assertEquals(BytecodePointcutKind.INVOKE, PointcutKinds.kindOf(0x10.toByte()))
    }
}
