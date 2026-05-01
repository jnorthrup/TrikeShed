package borg.trikeshed.jules.client

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class JulesConfixParserTest {
    @Test
    fun testParseSessionJson() = runTest {
        val json = """{"id": "test-session", "title": "My test session"}"""
        val parsed = parseJulesSessionConfix(json, coroutineContext)
        // Check that the returned string from Confix parser representation isn't totally empty.
        assertTrue(parsed.isNotEmpty())
    }
}
