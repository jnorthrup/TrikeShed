package borg.trikeshed.fetch

import borg.trikeshed.platform.HttpResponse
import borg.trikeshed.platform.httpGet // This is the expect fun
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Test-specific "actual" implementations or fakes.
// In a real build, this would be configured via build system for test source sets.
// For now, we'll assume a mechanism to swap implementations for testing.

// --- Test Doubles/Fakes ---
private var lastHttpGetUrl: String? = null
private var lastHttpGetHeaders: Map<String, String>? = null
private var httpGetResponse: HttpResponse = HttpResponse(200, "Default test body", mapOf("Content-Type" to listOf("text/plain")))

private var stdOutOutput = StringBuilder()
private var stdErrOutput = StringBuilder()
private var programExitCode: Int? = null


// Fake implementations for platform functions used by CurlHandler
// These would ideally be part of a proper KMP test setup (e.g. in test source set's actuals)
// For now, defining them here to illustrate the test logic.

// Faking the expect functions for the purpose of this test file.
// In a real scenario, these would be actual implementations in the test source set.
actual suspend fun httpGet(urlString: String, headers: Map<String, String>): HttpResponse {
    lastHttpGetUrl = urlString
    lastHttpGetHeaders = headers
    return httpGetResponse
}

actual fun writeToStdOut(message: String) {
    stdOutOutput.append(message)
}

actual fun writeToStdErr(message: String) {
    stdErrOutput.append(message)
}

actual fun exitProgram(exitCode: Int) {
    programExitCode = exitCode
    // In a real test, this might throw a specific exception to halt execution and be caught by the test.
}


class CurlHandlerTest {

    private fun setupTest(mockResponse: HttpResponse = HttpResponse(200, "Test body", mapOf("Content-Type" to listOf("text/plain")))) {
        lastHttpGetUrl = null
        lastHttpGetHeaders = null
        httpGetResponse = mockResponse
        stdOutOutput.clear()
        stdErrOutput.clear()
        programExitCode = null
    }

    @Test
    fun testHandleCurlInvocation_basicGet() = runBlocking {
        setupTest()
        val args = listOf("http://example.com")
        val exitCode = handleCurlInvocation(args)

        assertEquals(0, exitCode, "Exit code should be 0 for successful GET.")
        assertEquals("http://example.com", lastHttpGetUrl)
        assertTrue(lastHttpGetHeaders?.isEmpty() ?: false)
        assertEquals("Test body", stdOutOutput.toString())
        assertTrue(stdErrOutput.toString().isEmpty())
    }

    @Test
    fun testHandleCurlInvocation_noUrl() = runBlocking {
        setupTest()
        val args = emptyList<String>()
        val exitCode = handleCurlInvocation(args)

        assertEquals(1, exitCode, "Exit code should be 1 when no URL is provided.")
        assertTrue(stdErrOutput.toString().contains("trike-curl: try 'trike-curl --help' for more information"), "Error message for no URL.")
    }

    @Test
    fun testHandleCurlInvocation_includeHeaders() = runBlocking {
        setupTest(HttpResponse(200, "Body", mapOf("X-Test" to listOf("Value"), "Connection" to listOf("close"))))
        val args = listOf("-i", "http://example.com/path")
        val exitCode = handleCurlInvocation(args)

        assertEquals(0, exitCode)
        assertEquals("http://example.com/path", lastHttpGetUrl)
        
        val output = stdOutOutput.toString()
        assertTrue(output.startsWith("HTTP/1.1 200 OK\n"), "Should include HTTP status line.")
        assertTrue(output.contains("X-Test: Value\n"), "Should include custom header.")
        assertTrue(output.contains("Connection: close\n"), "Should include connection header.")
        assertTrue(output.endsWith("\n\nBody"), "Should have blank line then body.")
    }

    @Test
    fun testHandleCurlInvocation_customHeader() = runBlocking {
        setupTest()
        val args = listOf("-H", "Authorization: Bearer token", "http://secure.example.com")
        val exitCode = handleCurlInvocation(args)

        assertEquals(0, exitCode)
        assertEquals("http://secure.example.com", lastHttpGetUrl)
        assertEquals(mapOf("Authorization" to "Bearer token"), lastHttpGetHeaders)
    }
    
    @Test
    fun testHandleCurlInvocation_multipleCustomHeaders() = runBlocking {
        setupTest()
        val args = listOf(
            "-H", "X-First: Foo",
            "-H", "X-Second: Bar",
            "http://headers.example.com"
        )
        val exitCode = handleCurlInvocation(args)

        assertEquals(0, exitCode)
        assertEquals("http://headers.example.com", lastHttpGetUrl)
        assertEquals(mapOf("X-First" to "Foo", "X-Second" to "Bar"), lastHttpGetHeaders)
    }

    @Test
    fun testHandleCurlInvocation_httpError() = runBlocking {
        setupTest(HttpResponse(404, "Not Found Page", mapOf(), "Error body from stream if any"))
        val args = listOf("http://example.com/notfound")
        val exitCode = handleCurlInvocation(args)

        assertEquals(1, exitCode, "Exit code should be non-zero for HTTP error.")
        assertEquals("http://example.com/notfound", lastHttpGetUrl)
        // Curl typically prints error page to stdout
        assertEquals("Not Found PageError body from stream if any", stdOutOutput.toString()) 
    }
    
    @Test
    fun testHandleCurlInvocation_includeHeadersOnError() = runBlocking {
        setupTest(HttpResponse(403, "Forbidden Page", mapOf("Content-Type" to listOf("text/html")), "Forbidden Error"))
        val args = listOf("-i", "http://example.com/forbidden")
        val exitCode = handleCurlInvocation(args)

        assertEquals(1, exitCode)
        val output = stdOutOutput.toString()
        assertTrue(output.startsWith("HTTP/1.1 403 Forbidden\n"))
        assertTrue(output.contains("Content-Type: text/html\n"))
        assertTrue(output.endsWith("\n\nForbidden PageForbidden Error"))
    }
    
    @Test
    fun testHandleCurlInvocation_invalidHeaderFormat() = runBlocking {
        setupTest()
        val args = listOf("-H", "NoColonValue", "http://example.com")
        val exitCode = handleCurlInvocation(args)
        assertEquals(1, exitCode)
        assertTrue(stdErrOutput.toString().contains("trike-curl: invalid header format 'NoColonValue'"))
    }
    
    @Test
    fun testHandleCurlInvocation_headerMissingParameter() = runBlocking {
        setupTest()
        val args = listOf("-H") // Missing the header value itself
        // Url is also missing, but -H error should come first if parser is sequential
        // Or no URL error if that's checked first. Current parser checks URL last.
        // Let's add a URL to isolate -H error
        val exitCodeForH = handleCurlInvocation(listOf("-H", "http://example.com"))
        assertEquals(1, exitCodeForH)
        assertTrue(stdErrOutput.toString().contains("trike-curl: option -H: requires parameter"))

        stdErrOutput.clear() // Clear for next check

        val exitCodeForHelp = handleCurlInvocation(listOf("--header")) // Missing the header value itself
        assertEquals(1, exitCodeForHelp)
        assertTrue(stdErrOutput.toString().contains("trike-curl: option --header: requires parameter"))
    }

    // This test demonstrates how one might test the "Not Implemented" path for httpGet
    @Test
    fun testHandleCurlInvocation_httpGetNotImplemented() = runBlocking {
        setupTest(HttpResponse(0, "", emptyMap(), "Not implemented on Native platform."))
        val args = listOf("http://example.com")
        val exitCode = handleCurlInvocation(args)

        assertEquals(1, exitCode)
        assertTrue(stdErrOutput.toString().contains("trike-curl: (error) Network request functionality is not implemented for the current platform."))
    }
}
// Note: This test file includes `actual` fakes for `expect` functions.
// In a full KMP project, these test-specific actuals would live in `commonTest/kotlin/<platform>/...`
// or be managed by a mocking framework compatible with KMP.
// The `exitProgram` actual is also faked here.
// The `runBlocking` is used here because `handleCurlInvocation` is a suspend function.
// Standard KMP tests use `kotlinx.coroutines.test.runTest`.
// This is a simplified representation for demonstration.

// Placeholder actuals for the test environment.
// These would typically be in src/commonTest/kotlin/borg/trikeshed/platform/System.kt
// (or platform specific test actuals)
// This is a simplified approach for this context.
internal actual suspend fun httpGet(urlString: String, headers: Map<String, String>): HttpResponse {
    lastHttpGetUrl = urlString
    lastHttpGetHeaders = headers
    return httpGetResponse
}

internal actual fun writeToStdOut(message: String) {
    stdOutOutput.append(message)
}

internal actual fun writeToStdErr(message: String) {
    stdErrOutput.append(message)
}

internal actual fun exitProgram(exitCode: Int) {
    programExitCode = exitCode
    // In real tests, might throw a custom exception to signal exit for assertion.
}
