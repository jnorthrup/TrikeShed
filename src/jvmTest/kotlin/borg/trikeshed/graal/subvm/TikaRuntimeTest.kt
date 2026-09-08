package borg.trikeshed.graal.subvm

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TikaRuntimeTest {

    @Test
    fun missingModuleRefusesBeforeHostClasspathFallback() = withSubvmHome(Files.createTempDirectory("tika-empty-")) {
        val err = assertFailsWith<IllegalStateException> {
            TikaRuntime.extract(
                bytes = "host fallback must not parse".encodeToByteArray(),
                name = "fallback.html",
                mediaType = "text/html",
            )
        }
        val msg = err.message.orEmpty()
        assertTrue("guest module 'tika' is not installed" in msg, msg)
        assertTrue("installTika" in msg, msg)
    }

    @Test
    fun unicodeHtmlExtractsThroughMountedTika() {
        requireTikaModule()
        val text = "LLP curator feed unicode: cafe \u00e9lan \u03a9 \u6f22\u5b57"
        val html = "<!doctype html><html><body><p>$text</p></body></html>"
        val out = TikaRuntime.extract(
            bytes = html.encodeToByteArray(),
            name = "llp-unicode.html",
            mediaType = "text/html",
        )

        assertTrue(text in out.text, "expected full unicode text in managed Tika output, got: ${out.text}")
        assertEquals("llp-unicode.html", out.name)
        assertEquals("text/html", out.mediaType)
        assertEquals(TikaRuntime.MODULE, out.module)
        assertTrue(out.metadata.isNotEmpty(), "expected Tika metadata")
    }

    @Test
    fun byteExtractionKeepsResourceNameMetadata() {
        requireTikaModule()
        val out = TikaRuntime.extract(
            bytes = "<html><body><p>byte sidecar token rtx-17</p></body></html>".encodeToByteArray(),
            name = "byte-source.html",
            mediaType = "text/html",
        )
        assertTrue("rtx-17" in out.text, "expected byte text in managed Tika output, got: ${out.text}")
        assertEquals("byte-source.html", out.name)
        assertTrue(
            out.metadata["resourceName"].orEmpty().contains("byte-source.html"),
            "resourceName metadata missing from ${out.metadata}",
        )
    }

    @Test
    fun longUnicodeExtractKeepsTailBeyondDefaultHandlerLimit() {
        requireTikaModule()
        val tail = "TAIL-SENTINEL-\u03c0-\u7d42"
        val text = buildString {
            append("LONG-BEGIN ")
            repeat(30_000) { append("\u00e9\u03a9\u6f22\u5b57") }
            append(' ')
            append(tail)
        }
        val out = TikaRuntime.extract(
            bytes = "<html><body><p>$text</p></body></html>".encodeToByteArray(),
            name = "long-unicode.html",
            mediaType = "text/html",
        )

        assertTrue(out.text.length > 100_000, "expected full text beyond default Tika limit, got ${out.text.length}")
        assertTrue(tail in out.text, "expected long tail sentinel in managed Tika output")
    }

    @Test
    fun contextLoaderIsRestoredAfterSuccessAndParseFailure() {
        requireTikaModule()
        val thread = Thread.currentThread()
        val prior = thread.contextClassLoader
        val sentinel = object : ClassLoader(null) {}
        thread.contextClassLoader = sentinel
        try {
            TikaRuntime.extract(
                bytes = "<html><body><p>context loader success</p></body></html>".encodeToByteArray(),
                name = "loader.html",
                mediaType = "text/html",
            )
            assertSame(sentinel, thread.contextClassLoader, "context loader was not restored after success")

            val failure = runCatching {
                TikaRuntime.extract(
                    bytes = "%PDF-1.4\nnot a valid pdf body\n%%EOF".encodeToByteArray(),
                    name = "broken.pdf",
                    mediaType = "application/pdf",
                )
            }.exceptionOrNull()
            assertNotNull(failure, "expected corrupt PDF parse failure")
            assertSame(sentinel, thread.contextClassLoader, "context loader was not restored after parse failure")
        } finally {
            thread.contextClassLoader = prior
        }
    }

    private fun requireTikaModule() {
        assertTrue(
            GuestModules.isInstalled(TikaRuntime.MODULE),
            "guest module '${TikaRuntime.MODULE}' is not installed - run: ./gradlew -p utils/subvm installTika",
        )
    }

    private fun withSubvmHome(home: java.nio.file.Path, block: () -> Unit) {
        val prior = System.getProperty(GuestModules.HOME_PROPERTY)
        System.setProperty(GuestModules.HOME_PROPERTY, home.toAbsolutePath().toString())
        try {
            block()
        } finally {
            if (prior == null) System.clearProperty(GuestModules.HOME_PROPERTY)
            else System.setProperty(GuestModules.HOME_PROPERTY, prior)
            home.toFile().deleteRecursively()
        }
    }
}
