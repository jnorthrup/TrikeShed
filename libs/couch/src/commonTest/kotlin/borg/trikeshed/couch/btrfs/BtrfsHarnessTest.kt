package borg.trikeshed.couch.btrfs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class BtrfsHarnessTest {
    @Test
    fun testHarnessExecutesSuccessfully() = runTest {
        val sandbox = BtrfsSandboxElement()
        sandbox.open()

        val harness = BtrfsHarness()
        val result = harness.runDemo(sandbox)

        assertTrue(result.contains("Harness run complete"), "Harness output should report completion.")
        println(result)
        assertTrue(result.contains("stored 2 entries"), "Should have two WAL entries.")
        assertTrue(result.contains("Sandbox tree size is"), "Tree size should be calculated.")
        println(result)
    }
}
