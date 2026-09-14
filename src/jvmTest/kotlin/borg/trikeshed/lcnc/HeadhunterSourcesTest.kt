package borg.trikeshed.lcnc

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.graal.subvm.TikaRuntime
import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.HtxExchangeLifecycle
import borg.trikeshed.htx.HtxExchangeResult
import borg.trikeshed.htx.HtxExchangeState
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.HtxRouteService
import borg.trikeshed.htx.headerValue
import borg.trikeshed.htx.htxFrames
import borg.trikeshed.htx.htxHeaders
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.j
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.nio.channels.spi.EgressAllowlist
import keymux.CouchKeyStore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadhunterSourcesTest {
    fun sources(): HeadhunterSources {
        val cas = CasStore.inMemory()
        val privateCas = CasStore.inMemory()
        return HeadhunterSources(cas, CouchKeyStore(Couch("source-auth", CouchStoreFactory.casBacked(privateCas), privateCas)))
    }

    @Test
    fun importedEvidenceRetainsOriginalAndRepeatableCorrections() = runTest {
        val sources = sources()
        val original = "page header\nSTART Kotlin engineer — Montréal\nEND menu"
        val config = mapOf("extraction" to mapOf("startAfter" to "START ", "endBefore" to "\nEND",
            "replacements" to mapOf("engineer" to "developer")))
        val first = sources.capture(mapOf("text" to original, "filename" to "experience.txt"), config)
        assertEquals("ready", first["status"])
        assertEquals("Kotlin developer — Montréal", first["text"])
        val cid = ContentId(assertNotNull(first["originalCid"] as? String))
        assertContentEquals(original.encodeToByteArray(), sources.cas.get(cid))
        assertEquals(original.encodeToByteArray().size, first["originalBytes"])
        val repeat = sources.capture(mapOf("originalCid" to cid.value, "filename" to "experience.txt"), config)
        assertEquals(first["text"], repeat["text"])
        assertEquals(first["textCid"], repeat["textCid"])
        assertEquals(first["extractedTextCid"], repeat["extractedTextCid"])
        assertEquals(cid.value, repeat["originalCid"])
    }

    @Test
    fun absentCorrectionMarkerAndBadEncodingPreserveOriginalWithoutInventingText() = runTest {
        val sources = sources()
        val absent = sources.capture(mapOf("text" to "actual source"),
            mapOf("extraction" to mapOf("startAfter" to "not in source")))
        assertEquals("extraction_failed", absent["status"])
        assertNull(absent["text"])
        assertNotNull(absent["originalCid"])
        val bytes = byteArrayOf(-1, -2, 0)
        val binary = sources.capture(mapOf("base64" to java.util.Base64.getEncoder().encodeToString(bytes),
            "filename" to "bad.txt", "mediaType" to "text/plain; charset=utf-8"))
        assertEquals("extraction_failed", binary["status"])
        assertNull(binary["text"])
        assertContentEquals(bytes, sources.cas.get(ContentId(binary["originalCid"] as String)))
    }

    @Test
    fun managedParserExtractsImportedHtmlPdfAndDocxFromTheirOriginalBytes() = runTest {
        assertTrue(GuestModules.isInstalled(TikaRuntime.MODULE), "Install the existing managed module: ./gradlew -p utils/subvm installTika")
        val sources = sources()
        suspend fun verify(bytes: ByteArray, filename: String) {
            val result = sources.capture(mapOf("base64" to java.util.Base64.getEncoder().encodeToString(bytes), "filename" to filename))
            assertEquals("ready", result["status"], result["error"] as? String)
            assertTrue((result["text"] as String).contains("Kotlin engineer source 7421"))
            assertEquals("tika-${TikaRuntime.TIKA_VERSION}", result["extractor"])
            assertContentEquals(bytes, sources.cas.get(ContentId(result["originalCid"] as String)))
        }
        verify("<html><body><p>Kotlin engineer source 7421</p></body></html>".encodeToByteArray(), "source.html")
        val content = "BT /F1 12 Tf 50 700 Td (Kotlin engineer source 7421) Tj ET"
        val objects = arrayOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
        )
        val pdf = StringBuilder("%PDF-1.4\n")
        val offsets = IntArray(objects.size)
        objects.forEachIndexed { i, obj -> offsets[i] = pdf.length; pdf.append("${i + 1} 0 obj\n$obj\nendobj\n") }
        val xref = pdf.length
        pdf.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { pdf.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        pdf.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        verify(pdf.toString().encodeToByteArray(), "resume.pdf")
        val document = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(document).use { zip ->
            fun part(name: String, xml: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(xml.encodeToByteArray())
                zip.closeEntry()
            }
            part("[Content_Types].xml", """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            part("_rels/.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            part("word/document.xml", """<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Kotlin engineer source 7421</w:t></w:r></w:p></w:body></w:document>""")
        }
        verify(document.toByteArray(), "resume.docx")
    }

    @Test
    fun sourceCredentialsAreScopedAndDoNotBecomeModelProvidersOrEvidence() = runTest {
        val sources = sources()
        val secret = "session=isolated-cookie-value"
        val saved = sources.saveCredential(mapOf("origin" to "https://jobs.example/jobs/", "cookie" to secret,
            "pathPrefix" to "/jobs", "credentialRef" to "isolated-source"))
        assertFalse(JsonSupport.stringify(saved).contains(secret))
        assertTrue(sources.credentials.listProviders().isEmpty())
        val requests = mutableListOf<HtxRequest>()
        val element = sourceElement { request ->
            requests += request
            when (requests.size) {
                1 -> HtxResponse(302, headers = htxHeaders("Location" j "/jobs/42"))
                2 -> HtxResponse(302, headers = htxHeaders("Location" j "https://listings.example/42"))
                else -> HtxResponse(200, ByteSeries("Role: Kotlin engineer"), htxHeaders("Content-Type" j "text/plain"))
            }
        }
        try {
            val result = withContext(element) { sources.capture(mapOf("url" to "https://jobs.example/jobs/"),
                mapOf("credentialRef" to saved["credentialRef"])) }
            assertEquals("ready", result["status"])
            assertEquals(secret, requests[0].headerValue("Cookie"))
            assertEquals(secret, requests[1].headerValue("Cookie"))
            assertNull(requests[2].headerValue("Cookie"))
            assertEquals("https://listings.example/42", result["finalUrl"])
            assertEquals("Role: Kotlin engineer", result["text"])
            assertFalse(JsonSupport.stringify(result).contains(secret))
            assertFalse(JsonSupport.stringify(result).contains("isolated-cookie-value"))
            assertEquals("identity", requests[0].headerValue("Accept-Encoding"))
        } finally { element.close() }
    }

    @Test
    fun blockedHttpRetainsResponseAndDoesNotBecomeAListing() = runTest {
        val sources = sources()
        val element = sourceElement { HtxResponse(403, ByteSeries("Sign in required"), htxHeaders("Content-Type" j "text/plain")) }
        try {
            val result = withContext(element) { sources.capture(mapOf("url" to "https://jobs.example/42")) }
            assertEquals("blocked", result["status"])
            assertEquals(403, result["httpStatus"])
            assertNull(result["text"])
            assertContentEquals("Sign in required".encodeToByteArray(), sources.cas.get(ContentId(result["originalCid"] as String)))
        } finally { element.close() }
    }

    @Test
    fun authenticatedRedirectNeverDowngradesTlsOrEscapesItsPath() = runTest {
        val sources = sources()
        sources.saveCredential(mapOf("url" to "https://jobs.example/", "value" to "session=only-test-secret",
            "pathPrefix" to "/jobs", "credentialRef" to "test"))
        val requests = mutableListOf<HtxRequest>()
        val element = sourceElement {
            requests += it
            HtxResponse(302, headers = htxHeaders("Location" j "http://jobs.example/jobs/42"))
        }
        try {
            val result = withContext(element) { sources.capture(mapOf("url" to "https://jobs.example/jobs/42"), mapOf("credentialRef" to "test")) }
            assertEquals("blocked", result["status"])
            assertEquals(1, requests.size)
            val outside = withContext(element) { sources.capture(mapOf("url" to "https://jobs.example/jobs-other/42"), mapOf("credentialRef" to "test")) }
            assertEquals("configuration_required", outside["status"])
            val encodedTraversal = withContext(element) { sources.capture(mapOf("url" to "https://jobs.example/jobs/%2e%2e/private"), mapOf("credentialRef" to "test")) }
            assertEquals("configuration_required", encodedTraversal["status"])
            assertEquals(1, requests.size)
        } finally { element.close() }
    }

    @Test
    fun reflectedCredentialsAndTransportExceptionTextDoNotEnterEvidence() = runTest {
        val sources = sources()
        sources.saveCredential(mapOf("url" to "https://jobs.example/", "value" to "session=isolated-secret", "credentialRef" to "test"))
        var throwFailure = false
        val element = sourceElement {
            if (throwFailure) error("transport accidentally included isolated-secret")
            HtxResponse(200, ByteSeries("session is isolated-secret"), htxHeaders("Content-Type" j "text/plain"))
        }
        try {
            for (throws in arrayOf(false, true)) {
                throwFailure = throws
                val result = withContext(element) { sources.capture(mapOf("url" to "https://jobs.example/"), mapOf("credentialRef" to "test")) }
                assertEquals(if (throws) "fetch_failed" else "blocked", result["status"])
                assertNull(result["originalCid"])
                assertNull(result["text"])
                assertFalse(JsonSupport.stringify(result).contains("isolated-secret"))
            }
        } finally { element.close() }
    }

    @Test
    fun missingTransportAndBrowserSourceAreExplicitConfigurationFailures() = runTest {
        val sources = sources()
        assertEquals("configuration_required", sources.capture(mapOf("url" to "https://jobs.example/"))["status"])
        assertEquals("unsupported", sources.capture(mapOf("url" to "https://jobs.example/"), mapOf("mode" to "browser"))["status"])
        assertEquals("configuration_required", sources.capture(mapOf("url" to "https://jobs.example/"),
            mapOf("credentialRef" to "missing"))["status"])
        val secretUrl = sources.capture(mapOf("url" to "https://jobs.example/?access_token=isolated-secret"))
        assertEquals("invalid", secretUrl["status"])
        assertFalse(JsonSupport.stringify(secretUrl).contains("isolated-secret"))
        assertFailsWith<SourceFailure> { sources.configuration(mapOf("cookie" to "isolated-secret")) }
        assertFailsWith<SourceFailure> { sources.saveCredential(mapOf("origin" to "http://jobs.example/", "cookie" to "isolated-secret")) }
        assertFailsWith<SourceFailure> { sources.saveCredential(mapOf("origin" to "https://jobs.example/", "cookie" to "isolated-secret\r\nX-Leak: yes")) }
    }

    @Test
    fun selectedSourceAndValidatedRedirectUseTheExistingEgressAdmission() = runTest {
        val sources = sources()
        val suffix = java.util.UUID.randomUUID().toString()
        val first = "source-$suffix.example"
        val second = "redirect-$suffix.example"
        val invalid = "invalid-$suffix.example"
        assertFalse(EgressAllowlist.permits(first))
        assertFalse(EgressAllowlist.permits(second))
        var count = 0
        val element = sourceElement { request ->
            assertTrue(EgressAllowlist.permits(request.target.host))
            if (count++ == 0) HtxResponse(302, headers = htxHeaders("Location" j "https://$second/jobs"))
            else HtxResponse(200, ByteSeries("source text"), htxHeaders("Content-Type" j "text/plain"))
        }
        try {
            val result = withContext(element) { sources.capture(mapOf("url" to "https://$first/jobs")) }
            assertEquals("ready", result["status"])
            assertTrue(EgressAllowlist.permits(first))
            assertTrue(EgressAllowlist.permits(second))
            val rejected = withContext(element) { sources.capture(mapOf("url" to "https://$invalid/?access_token=isolated-secret")) }
            assertEquals("invalid", rejected["status"])
            assertFalse(EgressAllowlist.permits(invalid))
            assertEquals(2, count)
        } finally { element.close() }
        val denied = sourceElement { throw SecurityException("egress denied by substrate including isolated-secret") }
        try {
            val result = withContext(denied) { sources.capture(mapOf("url" to "https://$first/jobs")) }
            assertEquals("configuration_required", result["status"])
            assertFalse(JsonSupport.stringify(result).contains("isolated-secret"))
        } finally { denied.close() }
    }

    suspend fun sourceElement(response: suspend (HtxRequest) -> HtxResponse): HtxElement = HtxElement(routeService = object : HtxRouteService {
        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult =
            HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response(request)), htxFrames())
    }).also { it.open() }
}
