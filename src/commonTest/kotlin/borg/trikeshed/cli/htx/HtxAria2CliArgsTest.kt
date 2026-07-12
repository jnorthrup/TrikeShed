package borg.trikeshed.cli.htx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HtxAria2CliArgsTest {
    @Test
    fun testParseValidCoreArgs() {
        val options = HtxAria2CliArgs.parse(arrayOf("--dir=/tmp", "--split=10", "http://example.com/file"))
        assertEquals("/tmp", options.dir)
        assertEquals(10, options.split)
        assertEquals(listOf("http://example.com/file"), options.uris)
    }

    @Test
    fun testFailOnUnsupportedArg() {
        assertFailsWith<UnsupportedOperationException> {
            HtxAria2CliArgs.parse(arrayOf("--bt-tracker=tracker"))
        }
    }
}
