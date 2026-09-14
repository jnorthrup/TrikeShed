package borg.trikeshed.lcnc

import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.graal.subvm.TikaRuntime
import borg.trikeshed.htx.HtxKey
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.headerValue
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.htx.withHeader
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toArray
import borg.trikeshed.userspace.nio.channels.spi.EgressAllowlist
import keymux.CouchKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

typealias HeadhunterSourceBytes = Join<ByteArray, Map<String, Any?>>

/** Acquisition borrows the caller's common HTX reactor. The host owns CAS and credential lifetime. */
class HeadhunterSources(
    val cas: CasStore,
    val credentials: CouchKeyStore,
) {
    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_TEXT_CHARS = 2 * 1024 * 1024
    }

    /** Direct private HTTP entry only: never pass this input through LCNC params or evidence. */
    suspend fun saveCredential(input: Map<String, Any?>): Map<String, Any?> {
        val uri = sourceUri(input["url"] as? String ?: input["origin"] as? String
            ?: throw SourceFailure("invalid", "Credential URL is required"))
        if (uri.scheme != "https") throw SourceFailure("invalid", "Source credentials require an HTTPS origin")
        val header = when ((input["header"] as? String ?: "Cookie").lowercase()) {
            "cookie" -> "Cookie"
            "authorization" -> "Authorization"
            else -> throw SourceFailure("invalid", "Choose Cookie or Authorization")
        }
        val secret = input["value"] as? String ?: input["cookie"] as? String
            ?: throw SourceFailure("invalid", "Credential value is required")
        if (secret.length > 32_768 || secret.isBlank() || secret.any { it == '\r' || it == '\n' || it == '\u0000' })
            throw SourceFailure("invalid", "Credential must be one nonempty header value")
        val ref = input["credentialRef"] as? String ?: UUID.randomUUID().toString()
        if (!ref.matches(Regex("[A-Za-z0-9_-]{1,100}"))) throw SourceFailure("invalid", "Invalid credential reference")
        val path = input["pathPrefix"] as? String ?: "/"
        if (!path.startsWith('/') || path.any { it == '?' || it == '#' || it == '\r' || it == '\n' })
            throw SourceFailure("invalid", "Credential path must be an absolute URL path")
        credentials.storeSourceCredential(ref, sourceOrigin(uri), path, header, secret)
        return mapOf("credentialRef" to ref, "origin" to sourceOrigin(uri), "pathPrefix" to path,
            "header" to header, "configured" to true)
    }

    /** Safe fields to persist on the versioned source record, including reusable text corrections. */
    fun configuration(input: Map<String, Any?>): Map<String, Any?> {
        val allowed = setOf("credentialRef", "mode", "extraction")
        if (input.keys.any { it !in allowed })
            throw SourceFailure("invalid", "Source configuration accepts credentialRef, mode and extraction only; enter secrets separately")
        val mode = if (input["mode"] == null) "http" else input["mode"] as? String
            ?: throw SourceFailure("invalid", "Source mode must be text")
        if (mode != "http" && mode != "browser") throw SourceFailure("invalid", "Source mode must be http or browser")
        val extraction = input["extraction"]?.let {
            @Suppress("UNCHECKED_CAST")
            it as? Map<String, Any?> ?: throw SourceFailure("invalid", "Extraction configuration must be an object")
        } ?: emptyMap()
        if (extraction.keys.any { it !in setOf("startAfter", "endBefore", "replacements") })
            throw SourceFailure("invalid", "Extraction supports startAfter, endBefore and literal replacements")
        for (key in arrayOf("startAfter", "endBefore")) {
            val value = extraction[key] ?: continue
            if (value !is String || value.isEmpty() || value.length > 10_000)
                throw SourceFailure("invalid", "Extraction markers must be nonempty text of at most 10000 characters")
        }
        extraction["replacements"]?.let { value ->
            val replacements = value as? Map<*, *> ?: throw SourceFailure("invalid", "Replacements must be a text-to-text object")
            if (replacements.size > 100 || replacements.any { (from, to) ->
                from !is String || from.isEmpty() || from.length > 10_000 || to !is String || to.length > 10_000
            }) throw SourceFailure("invalid", "Use at most 100 nonempty literal text replacements")
        }
        val result = linkedMapOf<String, Any?>("mode" to mode, "extraction" to extraction)
        input["credentialRef"]?.let {
            if (it !is String || !it.matches(Regex("[A-Za-z0-9_-]{1,100}")))
                throw SourceFailure("invalid", "Invalid credential reference")
            result["credentialRef"] = it
        }
        return result
    }

    /** Preserves the original before parsing. Failed extraction never masquerades as a listing. */
    suspend fun capture(input: Map<String, Any?>, config: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>("acquiredAtMs" to System.currentTimeMillis())
        try {
            val sourceConfig = configuration(config)
            val modes = arrayOf("url", "text", "base64", "originalCid").count { input[it] != null }
            if (modes != 1) throw SourceFailure("invalid", "Provide exactly one of URL, text, file bytes or originalCid")
            val source = if (input["url"] != null) {
                if (sourceConfig["mode"] == "browser")
                    throw SourceFailure("unsupported", "Browser-session extraction is not installed. Import the page's text or file, or configure a static HTTP source")
                fetch(input["url"] as? String ?: throw SourceFailure("invalid", "URL must be text"), sourceConfig)
            } else {
                val bytes = when {
                    input["text"] != null -> {
                        val text = input["text"] as? String ?: throw SourceFailure("invalid", "Text input must be text")
                        if (text.length > MAX_BYTES) throw SourceFailure("too_large", "Input exceeds 8 MiB")
                        text.encodeToByteArray()
                    }
                    input["base64"] != null -> {
                        val encoded = input["base64"] as? String ?: throw SourceFailure("invalid", "File bytes must be base64")
                        if (encoded.length > (MAX_BYTES * 4L / 3L + 4L)) throw SourceFailure("too_large", "Input exceeds 8 MiB")
                        try { java.util.Base64.getDecoder().decode(encoded) }
                        catch (_: IllegalArgumentException) { throw SourceFailure("invalid", "File bytes are not valid base64") }
                    }
                    else -> {
                        val cid = try { ContentId(input["originalCid"] as? String ?: "") }
                        catch (_: IllegalArgumentException) { throw SourceFailure("invalid", "Invalid original content reference") }
                        cas.get(cid) ?: throw SourceFailure("missing", "Original source bytes are unavailable")
                    }
                }
                bytes j mapOf("acquisition" to if (input["originalCid"] != null) "stored" else "import")
            }
            val bytes = source.a
            result.putAll(source.b)
            if (bytes.size > MAX_BYTES) throw SourceFailure("too_large", "Input exceeds 8 MiB")
            result["originalCid"] = cas.put(bytes).value
            result["originalBytes"] = bytes.size
            val filename = (input["filename"] as? String ?: input["name"] as? String ?: source.b["filename"] as? String
                ?: if (input["text"] != null) "source.txt" else "source").substringAfterLast('/').substringAfterLast('\\').take(255)
            val mediaType = input["mediaType"] as? String ?: source.b["mediaType"] as? String
                ?: if (input["text"] != null) "text/plain; charset=utf-8" else mediaType(filename)
            result["filename"] = filename
            result["mediaType"] = mediaType
            result["configuration"] = sourceConfig
            (source.b["httpStatus"] as? Int)?.let { status ->
                if (status !in 200..299) throw SourceFailure(
                    if (status in arrayOf(401, 403, 407, 429)) "blocked" else "fetch_failed",
                    "Source returned HTTP $status; original response preserved and no listing inferred",
                )
                if (status == 206) throw SourceFailure("blocked", "Source returned partial content; import a complete document")
                val encoding = source.b["contentEncoding"] as? String
                if (encoding != null && encoding != "identity")
                    throw SourceFailure("unsupported", "Source ignored the identity encoding request; original preserved. Import a decoded document")
            }
            val text = if (plainText(mediaType)) {
                result["extractor"] = "text"
                decodeSourceText(bytes, mediaType)
            } else {
                if (!GuestModules.isInstalled(TikaRuntime.MODULE))
                    throw SourceFailure("configuration_required", "Original preserved. Install the managed Tika module for HTML, PDF, DOCX and other document extraction, or import plain text")
                result["extractor"] = "tika-${TikaRuntime.TIKA_VERSION}"
                val extracted = try {
                    TikaRuntime.extract(bytes, filename, mediaType, options = TikaRuntime.TikaOptions(
                        limits = TikaRuntime.TikaLimits(MAX_BYTES, MAX_TEXT_CHARS),
                        ocr = TikaRuntime.OcrOptions(pdfStrategy = TikaRuntime.PdfOcrStrategy.NO_OCR),
                    ))
                } catch (failure: CancellationException) { throw failure }
                catch (_: Exception) { throw SourceFailure("extraction_failed", "Original preserved. The document parser could not extract this file; import its text or a supported export") }
                extracted.text
            }
            val credential = (sourceConfig["credentialRef"] as? String)?.let { credentials.readSourceCredential(it) }
            if (credential != null && credentialValues(credential).any { it.length >= 4 && text.contains(it) })
                throw SourceFailure("blocked", "Extracted content reflected a credential and was not admitted as text evidence")
            result["extractedTextCid"] = cas.put(text.encodeToByteArray()).value
            @Suppress("UNCHECKED_CAST")
            val corrected = sourceText(text, sourceConfig["extraction"] as Map<String, Any?>)
            if (credential != null && credentialValues(credential).any { it.length >= 4 && corrected.contains(it) })
                throw SourceFailure("blocked", "Extraction corrections contain a credential; enter credentials separately")
            if (corrected.isBlank()) throw SourceFailure("empty", "Original preserved but no readable text was extracted. A scanned document or JavaScript-only page needs a text export")
            if (corrected.length > MAX_TEXT_CHARS) throw SourceFailure("too_large", "Extracted text exceeds 2 MiB of characters")
            result["text"] = corrected
            result["textCid"] = cas.put(corrected.encodeToByteArray()).value
            result["status"] = "ready"
        } catch (failure: CancellationException) { throw failure }
        catch (failure: SourceFailure) {
            result["status"] = failure.code
            result["error"] = failure.message
        }
        return result
    }

    suspend fun fetch(url: String, config: Map<String, Any?>): HeadhunterSourceBytes {
        val initial = sourceUri(url)
        val ref = config["credentialRef"] as? String
        val credential = ref?.let { credentials.readSourceCredential(it)
            ?: throw SourceFailure("configuration_required", "The saved source credential is unavailable; enter it in source configuration") }
        if (credential != null && !credentialApplies(credential, initial))
            throw SourceFailure("configuration_required", "Source URL is outside the credential's HTTPS origin and path")
        val htx = currentCoroutineContext()[HtxKey]
            ?: throw SourceFailure("configuration_required", "The common HTTP/TLS reactor is unavailable")
        var current = initial
        return withTimeoutOrNull(45_000L) {
            repeat(6) { redirects ->
                // An operator-selected source admits its validated destination through the existing gate.
                EgressAllowlist.allowUrl(sourceUrl(current))
                if (!EgressAllowlist.permits(current.host.lowercase()))
                    throw SourceFailure("configuration_required", "The source destination was not admitted by the egress policy")
                var request: HtxRequest = parseHtxRequest(sourceUrl(current))
                    .withHeader("Accept", "text/html,application/xhtml+xml,text/plain,application/pdf,application/json;q=0.9,*/*;q=0.5")
                    .withHeader("Accept-Encoding", "identity")
                val admitted = credential?.takeIf { credentialApplies(it, current) }
                if (admitted != null) request = request.withHeader(admitted["header"]!!, admitted["value"]!!)
                val response: HtxResponse = try { htx.request(request) }
                catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) {
                    if (generateSequence<Throwable>(failure) { it.cause }.take(8).any {
                        it is SecurityException || it.message?.contains("egress denied", ignoreCase = true) == true
                    }) throw SourceFailure("configuration_required", "The HTTP substrate denied the source destination; its egress admission needs configuration")
                    throw SourceFailure("fetch_failed", "HTTP/TLS acquisition failed; no listing was inferred")
                }
                if (response.status in arrayOf(301, 302, 303, 307, 308)) {
                    if (redirects == 5) throw SourceFailure("blocked", "Source exceeded five redirects")
                    val location = response.headers.headerValue("Location")
                        ?: throw SourceFailure("blocked", "Source redirect has no destination")
                    val next = sourceUri(try { current.resolve(location).toASCIIString() }
                        catch (_: IllegalArgumentException) { throw SourceFailure("blocked", "Invalid source redirect") })
                    if (current.scheme == "https" && next.scheme != "https")
                        throw SourceFailure("blocked", "HTTPS source redirected to an insecure transport")
                    current = next
                    return@repeat
                }
                val encoding = response.headers.headerValue("Content-Encoding")?.trim()?.lowercase()
                if (response.body.rem > MAX_BYTES) throw SourceFailure("too_large", "Input exceeds 8 MiB")
                val bytes = response.body.toArray()
                val raw = bytes.decodeToString()
                if (credential != null && credentialValues(credential).any { it.length >= 4 && raw.contains(it) })
                    throw SourceFailure("blocked", "Source reflected a credential. The response was not admitted as evidence")
                return@withTimeoutOrNull bytes j linkedMapOf(
                    "acquisition" to "http", "url" to sourceUrl(initial), "finalUrl" to sourceUrl(current),
                    "httpStatus" to response.status, "redirects" to redirects,
                    "filename" to current.path.substringAfterLast('/').ifBlank { "source.html" },
                    "mediaType" to response.headers.headerValue("Content-Type"),
                    "contentEncoding" to encoding,
                    "credentialRef" to ref,
                )
            }
            throw SourceFailure("blocked", "Source redirects did not complete")
        } ?: throw SourceFailure("fetch_failed", "HTTP/TLS acquisition timed out; no listing was inferred")
    }
}

class SourceFailure(val code: String, message: String) : IllegalArgumentException(message)

fun sourceUri(value: String): URI {
    val uri = try { URI(value.trim()).normalize() }
    catch (_: Exception) { throw SourceFailure("invalid", "Invalid source URL") }
    if (uri.scheme !in arrayOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.port > 65535 || uri.port == 0)
        throw SourceFailure("invalid", "Use an HTTP or HTTPS URL without embedded credentials")
    if (':' in uri.host) throw SourceFailure("unsupported", "The current HTTP reactor requires a hostname or IPv4 address")
    if (uri.rawQuery?.split('&')?.any {
        val key = java.net.URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8).lowercase().replace('-', '_')
        key in setOf("key", "api_key", "apikey", "token", "access_token", "auth", "authorization", "password", "signature", "sig", "x_amz_signature", "code", "session", "sessionid", "jwt", "bearer", "credential")
    } == true) throw SourceFailure("invalid", "URL contains a credential parameter; use a scoped credential header instead")
    return uri
}

fun sourceOrigin(uri: URI): String = "${uri.scheme}://${uri.host.lowercase()}" +
    if (uri.port == -1 || uri.port == (if (uri.scheme == "https") 443 else 80)) "" else ":${uri.port}"

fun sourceUrl(uri: URI): String = sourceOrigin(uri) + uri.rawPath.ifEmpty { "/" } +
    (uri.rawQuery?.let { "?$it" } ?: "")

fun credentialApplies(credential: Map<String, String?>, uri: URI): Boolean {
    val path = credential["path"] ?: return false
    val requested = URI(null, null, uri.path.ifEmpty { "/" }, null).normalize().path
    return uri.scheme == "https" && credential["origin"] == sourceOrigin(uri) &&
        (requested == path || requested.startsWith(if (path.endsWith('/')) path else "$path/"))
}

fun credentialValues(credential: Map<String, String?>): Sequence<String> {
    val value = credential["value"] ?: return emptySequence()
    return sequenceOf(value) + if (credential["header"] == "Cookie")
        value.splitToSequence(';').map { it.substringAfter('=', "").trim() }.filter { it.isNotEmpty() }
    else sequenceOf(value.substringAfter(' ', value))
}

fun mediaType(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
    "txt", "md", "csv", "tsv", "log" -> "text/plain; charset=utf-8"
    "json" -> "application/json"
    "html", "htm" -> "text/html"
    "pdf" -> "application/pdf"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    else -> "application/octet-stream"
}

fun plainText(mediaType: String): Boolean = mediaType.substringBefore(';').trim().lowercase() in
    setOf("text/plain", "text/markdown", "text/csv", "text/tab-separated-values", "application/json", "application/ld+json")

fun decodeSourceText(bytes: ByteArray, mediaType: String): String {
    val charset = mediaType.split(';').firstOrNull { it.trim().startsWith("charset=", ignoreCase = true) }
        ?.substringAfter('=')?.trim()?.trim('"') ?: "UTF-8"
    return try {
        java.nio.charset.Charset.forName(charset).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { throw SourceFailure("extraction_failed", "Original preserved. Text is not valid in its declared character encoding") }
}

fun sourceText(text: String, extraction: Map<String, Any?>): String {
    var selected = text
    (extraction["startAfter"] as? String)?.let { marker ->
        val index = selected.indexOf(marker)
        if (index < 0) throw SourceFailure("extraction_failed", "Original preserved. The configured start marker was not found")
        selected = selected.substring(index + marker.length)
    }
    (extraction["endBefore"] as? String)?.let { marker ->
        val index = selected.indexOf(marker)
        if (index < 0) throw SourceFailure("extraction_failed", "Original preserved. The configured end marker was not found")
        selected = selected.substring(0, index)
    }
    (extraction["replacements"] as? Map<*, *>)?.forEach { (from, to) -> selected = selected.replace(from as String, to as String) }
    return selected
}
