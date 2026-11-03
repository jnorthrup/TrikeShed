package borg.trikeshed.forge.server

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessProtocolPipes
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.userspace.nio.process.*
import kotlinx.coroutines.*
import java.net.URI
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

enum class PdfLinkStatus { AVAILABLE, UNAVAILABLE, LIMIT }

/** An occurrence, not a deduplicated target. Followable describes URI syntax, not fetch authority. */
data class PdfLink(val index: Int, val page: Int, val type: String, val uri: String,
                   val followable: Boolean, val reason: String?)

data class PdfLinkInventory(val sourceCid: ContentId, val status: PdfLinkStatus, val pages: Int?,
                            val links: Series<PdfLink>, val parser: String, val toolVersion: String?,
                            val coverage: String, val diagnostic: String?)

/**
 * Local annotation discovery only: no fetch, text extraction, model or shell. The executable is
 * operator bootstrap configuration, never request input. Poppler does not inventory GoTo actions
 * or printed citations with -url. The caller must apply its fetch policy to every selected target.
 * Limits: 32MiB input, 10,000 pages, 4,096 occurrences, 1MiB combined output, 45 seconds total.
 * ProcessCaptureChannel owns child cancellation/cleanup; this is not an OS sandbox or memory cap.
 */
class PdfLinks(private val executable: String = "pdfinfo") {
    init { require(executable.isNotBlank() && '\u0000' !in executable) }

    suspend fun discover(bytes: ByteArray, expectedCid: ContentId? = null): PdfLinkInventory {
        require(bytes.size in 1..INPUT_LIMIT) { "PDF input must be between 1 byte and 32MiB" }
        val source = bytes.copyOf()
        val cid = ContentId.of(source)
        require(expectedCid == null || expectedCid == cid) { "PDF source identity differs" }
        val files = coroutineContext[FileOperations] ?: error("PDF links require FileOperations")
        val started = TimeSource.Monotonic.markNow()
        val directory = withContext(NonCancellable + fileIoContext) { files.createTempDir("trikeshed-pdf-links-") }
        val path = files.resolvePath(directory, "source.pdf")
        var pages: Int? = null
        var version: String? = null
        var capturedBytes = 0
        fun result(status: PdfLinkStatus, links: Series<PdfLink> = emptySeriesOf(), diagnostic: String? = null) =
            PdfLinkInventory(cid, status, pages, links, PARSER, version, COVERAGE, diagnostic)
        suspend fun capture(arguments: List<String>): String {
            val remaining = TIME_LIMIT - started.elapsedNow().inWholeMilliseconds
            if (remaining <= 0 || capturedBytes >= OUTPUT_LIMIT) throw Limit("PDF discovery time/output limit")
            val pipes = JvmProcessProtocolPipes.open(command = { _, _ -> arguments },
                environment = _m["LANG" j "C", "LC_ALL" j "C"], mergeError = true)
            val output = ProcessCaptureChannel(coroutineContext, pipes, maxBytes = OUTPUT_LIMIT - capturedBytes,
                timeoutMillis = remaining, failOnOverflow = true).await()
            capturedBytes += output.bytes.size
            if (output.timedOut || output.dropped != 0L) throw Limit("PDF discovery time/output limit")
            check(output.exit == 0) { "pdfinfo exited ${output.exit}" }
            return output.bytes.decodeToString(throwOnInvalidSequence = true)
        }
        try {
            withContext(fileIoContext) {
                files.write(path, source)
                check(ContentId.of(files.readAllBytes(path)) == cid) { "PDF temporary source identity differs" }
            }
            version = Regex("(?m)^pdfinfo version ([^\\r\\n]+)$")
                .find(capture(_l[executable, "-v"]))?.groupValues?.get(1)
                ?: error("pdfinfo version output unavailable")
            pages = parsePages(capture(_l[executable, path]))
            return result(PdfLinkStatus.AVAILABLE, parseUrls(capture(_l[executable, "-url", path]), pages))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (limit: ProcessOutputLimitExceeded) {
            return result(PdfLinkStatus.LIMIT, diagnostic = "PDF discovery output exceeds 1MiB")
        } catch (limit: Limit) {
            return result(PdfLinkStatus.LIMIT, diagnostic = limit.message)
        } catch (failure: Exception) {
            return result(PdfLinkStatus.UNAVAILABLE, diagnostic = failure.message?.take(512) ?: "PDF discovery failed")
        } finally {
            withContext(NonCancellable + fileIoContext) { files.deleteRecursively(directory) }
        }
    }

    companion object {
        const val INPUT_LIMIT = 32 * 1024 * 1024
        const val OUTPUT_LIMIT = 1024 * 1024
        const val PAGE_LIMIT = 10_000
        const val LINK_LIMIT = 4096
        const val TIME_LIMIT = 45_000L
        const val PARSER = "poppler-pdfinfo-url/1"
        const val COVERAGE = "URI annotations reported by pdfinfo -url only; excludes GoTo actions and printed citations"

        internal fun parsePages(text: String): Int {
            val matches = Regex("(?m)^Pages:\\s+(\\d+)\\s*$").findAll(text).toList()
            require(matches.size == 1) { "PDF page count unavailable" }
            val pages = matches.single().groupValues[1].toIntOrNull() ?: throw Limit("PDF page count limit")
            if (pages > PAGE_LIMIT) throw Limit("PDF exceeds 10000 pages")
            require(pages > 0) { "PDF page count unavailable" }
            return pages
        }

        internal fun parseUrls(text: String, pages: Int): Series<PdfLink> {
            val lines = text.lineSequence().filter { it.isNotBlank() }.iterator()
            require(lines.hasNext() && lines.next().matches(Regex("\\s*Page\\s+Type\\s+URL\\s*"))) {
                "PDF URL inventory header unavailable"
            }
            val links = mutableListOf<PdfLink>()
            for (line in lines) {
                if (links.size >= LINK_LIMIT) throw Limit("PDF exceeds 4096 URI occurrences")
                val row = Regex("\\s*(\\d+)\\s+(\\S+)\\s+(.+)").matchEntire(line)
                    ?: error("PDF URL occurrence cannot be located")
                val page = row.groupValues[1].toIntOrNull()
                require(page != null && page in 1..pages) { "PDF URL page outside source" }
                val uri = row.groupValues[3]
                val parsed = runCatching { URI(uri) }.getOrNull()
                val reason = when {
                    uri.any { it.isWhitespace() || it.isISOControl() } || parsed == null -> "invalid-uri"
                    !parsed.isAbsolute -> "internal-or-relative-reference"
                    parsed.scheme.lowercase() !in _s["http", "https"] -> "unsupported-scheme"
                    parsed.host.isNullOrBlank() -> "missing-host"
                    parsed.rawUserInfo != null -> "user-info"
                    else -> null
                }
                links.add(PdfLink(links.size, page, row.groupValues[2], uri, reason == null, reason))
            }
            return links.toSeries()
        }
    }

    private class Limit(message: String) : IllegalStateException(message)
}
