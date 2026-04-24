package borg.trikeshed.couch.process

import kotlin.test.*

class ProcessShellTest {
    @Test fun `exec returns result on echo`() {
        val r = ProcessShell().exec("echo", "hello")
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("hello"))
    }
    @Test fun `exec fails appropriately`() {
        val r = ProcessShell().exec("false")
        assertEquals(1, r.exitCode)
    }
}
