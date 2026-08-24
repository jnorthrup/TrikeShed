package borg.trikeshed.userspace.containment
import kotlin.test.Test
import kotlin.test.assertEquals
class PatchFormatTest {
    @Test
    fun testFormat() {
        val diff = "diff --git a/a b/b\n--- a/a\n+++ b/b\n@@ -1,4 +1,4 @@\n-import a\n \n \n+import a\n"
        println("ORIGINAL:")
        println(diff)
        println("FORMATTED:")
        println(DeterministicFormatter.format(diff))
    }
}
