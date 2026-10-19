package borg.trikeshed.docs

import borg.trikeshed.parse.reify
import kotlin.test.*

class DocumentCurationSurfaceTest {
    @Test fun retainedAttemptAndStageLinksPinCborWithoutLoadingOrExecutingAProgram() {
        val cid = "sha256:" + "a".repeat(64)
        val stage = "sha256:" + "b".repeat(64)
        val result = assertNotNull(DocumentSurface.curationResult(reify("""{
            "receiptCid":"$cid","record":{"toolReceipts":[{"stage":"MODEL","status":"FAILURE","receiptCid":"$stage"}]}
        }""")))
        val html = DocumentSurface.curationResultHtml(result)
        for (id in listOf(cid, stage)) assertContains(html, "/panels?content=$id&amp;syntax=CBOR")
        assertContains(html, "MODEL · failed")
        assertFalse(html.contains("?load="))
        assertEquals("", DocumentSurface.curationConfixLink("javascript:alert(1)", "unsafe"))
        assertEquals("", DocumentSurface.curationConfixLink(null, "missing"))
        assertContains(DocumentSurface.curationConfixLink(cid, "<label>"), "&lt;label&gt;")
    }

    fun render(record: String): String = DocumentSurface.curationResultHtml(assertNotNull(
        DocumentSurface.curationResult(reify("""{"receiptCid":"sha256:attempt","record":$record}"""))))

    @Test fun retainedFailureShowsStageOutcomesAndTheExactModelReceipt() {
        val html = render("""{
            "source":{"name":"Synthetic contract","text":"Acme pays Beta."},
            "toolReceipts":[
                {"stage":"NLP","status":"SUCCESS","tool":"StanfordCoreNLP","receiptCid":"sha256:nlp"},
                {"stage":"MODEL","status":"FAILURE","tool":"ModelMux","receiptCid":"sha256:model"},
                {"stage":"GROUNDING","status":"SKIPPED","tool":"grounding","receiptCid":"sha256:grounding"}
            ],
            "receipt":{"receiptId":"call-17","modelId":"configured-model","providerId":"configured-provider",
                "httpStatus":0,"latencyMs":10027,"cachedHit":false,"errorClass":"TimeoutCancellationException"}
        }""")
        assertContains(html, "Saved attempt")
        assertContains(html, "NLP · succeeded")
        assertContains(html, "MODEL · failed")
        assertContains(html, "GROUNDING · skipped")
        assertContains(html, "sha256:model")
        assertContains(html, "call-17")
        assertContains(html, "configured-model")
        assertContains(html, "configured-provider")
        assertContains(html, "No HTTP response (status 0)")
        assertContains(html, "10027 ms")
        assertContains(html, "Uncached call")
        assertContains(html, "TimeoutCancellationException")
        assertTrue(html.indexOf("MODEL · failed") < html.indexOf("Source text"))
        assertContains(html, "No statements are retained in this attempt.")
    }

    @Test fun httpSuccessDoesNotOverrideARecordedStageFailure() {
        val html = render("""{"toolReceipts":[{"stage":"MODEL","status":"FAILURE"}],
            "receipt":{"receiptId":"cached-9","httpStatus":200,"cachedHit":true,"latencyMs":0}}""")
        assertContains(html, "MODEL · failed")
        assertContains(html, "HTTP 200")
        assertContains(html, "Cached response")
        assertContains(html, "0 ms")
        assertFalse(html.contains("MODEL · succeeded"))
    }

    @Test fun historicalAndUnknownReceiptsNeverInventSuccessOrACall() {
        val historical = render("""{"source":{"name":"Historical"}}""")
        assertContains(historical, "No stage receipts are available for this attempt.")
        assertContains(historical, "No model-call receipt is available; a call is not established.")
        assertFalse(historical.contains("succeeded"))
        val unknown = render("""{"toolReceipts":[{"stage":"MODEL","status":"FUTURE"},{"stage":"NLP"}],"receipt":{}}""")
        assertContains(unknown, "MODEL · unrecognized status: FUTURE")
        assertContains(unknown, "NLP · status unavailable")
        assertContains(unknown, "HTTP status unavailable")
        assertContains(unknown, "Cache status unavailable")
        assertFalse(unknown.contains("HTTP 0"))
        assertFalse(unknown.contains("Uncached call"))
    }

    @Test fun receiptMetadataIsEscapedAndRawProviderErrorsAreNotAddedToThePanel() {
        val html = render("""{"toolReceipts":[{"stage":"<MODEL>","status":"<unknown>",
            "tool":"<script>tool</script>","receiptCid":"cid\"<tag>"}],
            "receipt":{"receiptId":"<id>","modelId":"<model>","providerId":"<provider>",
                "errorClass":"<failure>","errorMessage":"private-provider-diagnostic"}}""")
        for (value in listOf("MODEL", "unknown", "model", "provider", "failure", "id"))
            assertContains(html, "&lt;$value&gt;")
        assertContains(html, "&lt;script&gt;tool&lt;/script&gt;")
        assertContains(html, "cid&quot;&lt;tag&gt;")
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("private-provider-diagnostic"))
    }
}
