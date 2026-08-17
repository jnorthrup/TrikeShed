package borg.trikeshed.userspace.containment

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatchAstLinterTest {

    @Test
    fun testCleanPatch() {
        val patch = """
            --- a/test.kt
            +++ b/test.kt
            @@ -1,5 +1,5 @@
             class MyClass {
            -    val x = 1
            +    val x = 2
             }
        """.trimIndent()
        val result = PatchAstLinter.lint(patch)
        assertTrue(result.clean, "Patch should be clean")
    }

    @Test
    fun testHighEntropyIdentifier() {
        val patch = """
            --- a/test.kt
            +++ b/test.kt
            @@ -1,5 +1,5 @@
             class MyClass {
            -    val x = 1
            +    val abcdefghijklmnopqrstuvwxyz = 2
             }
        """.trimIndent()
        val result = PatchAstLinter.lint(patch)
        assertFalse(result.clean, "Patch should be blocked for high entropy identifier")
        assertTrue(result.reason!!.contains("High-entropy identifier detected"))
    }

    @Test
    fun testSuspiciousComment() {
        val longString = "A".repeat(201)
        val patch = """
            --- a/test.kt
            +++ b/test.kt
            @@ -1,5 +1,5 @@
             class MyClass {
            -    val x = 1
            +    // $longString
             }
        """.trimIndent()
        val result = PatchAstLinter.lint(patch)
        assertFalse(result.clean, "Patch should be blocked for suspicious comment")
        assertTrue(result.reason!!.contains("Suspicious comment payload (>200 chars)"))
    }

    @Test
    fun testSuspiciousString() {
        val longString = "A".repeat(201)
        val patch = """
            --- a/test.kt
            +++ b/test.kt
            @@ -1,5 +1,5 @@
             class MyClass {
            -    val x = 1
            +    val x = "$longString"
             }
        """.trimIndent()
        val result = PatchAstLinter.lint(patch)
        assertFalse(result.clean, "Patch should be blocked for suspicious string")
        assertTrue(result.reason!!.contains("Suspicious string payload (>200 chars)"))
    }

    @Test
    fun testWhitespaceModulationInline() {
        val inlineSpaces = " ".repeat(4) + "\t".repeat(4)
        val patch = """
            --- a/test.kt
            +++ b/test.kt
            @@ -1,5 +1,5 @@
             class MyClass {
            -    val x = 1
            +    val x$inlineSpaces= 2
             }
        """.trimIndent()
        val result = PatchAstLinter.lint(patch)
        assertFalse(result.clean, "Patch should be blocked for whitespace modulation")
        assertTrue(result.reason!!.contains("Whitespace modulation detected"))
    }

    @Test
    fun testWhitespaceModulationTrailing() {
        val trailingSpaces = " \t"
        val patch = """
            --- a/test.kt
            +++ b/test.kt
            @@ -1,5 +1,5 @@
             class MyClass {
            -    val x = 1
            +    val x = 2$trailingSpaces
             }
        """.trimIndent()
        val result = PatchAstLinter.lint(patch)
        assertFalse(result.clean, "Patch should be blocked for whitespace modulation")
        assertTrue(result.reason!!.contains("Whitespace modulation detected"))
    }
}
