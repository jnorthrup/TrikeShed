package borg.trikeshed.activejs

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class GraalEcmaLauncherTest {

    @Test
    fun testLauncherCreation() = runBlocking {
        val launcher = GraalEcmaLauncherImpl()
        assertNotNull(launcher)
    }

    @Test
    fun testPointcutEventStructure() {
        val event = PointcutEvent(
            seq = 1L,
            nano = System.nanoTime(),
            opcode = PointcutOpcode.L_GET,
            phase = "BEFORE",
            target = "TestClass#testField",
            value = "testValue"
        )
        
        assertEquals(1L, event.seq)
        assertEquals(PointcutOpcode.L_GET, event.opcode)
        assertEquals("BEFORE", event.phase)
        assertEquals("TestClass#testField", event.target)
        assertEquals("testValue", event.value)
    }

    @Test
    fun testPointcutOpcodes() {
        assertEquals(0xA5, PointcutOpcode.L_GET)
        assertEquals(0xA6, PointcutOpcode.L_SET)
        assertEquals(0xA7, PointcutOpcode.P_GET)
        assertEquals(0xA8, PointcutOpcode.P_SET)
        assertEquals(0x10, PointcutOpcode.CALL)
        assertEquals(0x20, PointcutOpcode.NVOK)
        assertEquals(0x34, PointcutOpcode.CONSTR)
        assertEquals(0x4C, PointcutOpcode.RETURN)
    }
}