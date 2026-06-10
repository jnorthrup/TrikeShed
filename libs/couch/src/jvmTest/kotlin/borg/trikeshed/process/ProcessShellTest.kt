package borg.trikeshed.process

import kotlin.test.*

class ProcessShellTest {
    @Test fun `exec returns result on echo`() {
        if (System.getProperty("os.name") == "Mac OS X") return
        val r = ProcessShell().exec("echo", "hello")
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("hello"))
    }
    @Test fun `exec fails appropriately`() {
        if (System.getProperty("os.name") == "Mac OS X") return
        val r = ProcessShell().exec("false")
        assertEquals(1, r.exitCode)
    }
}
