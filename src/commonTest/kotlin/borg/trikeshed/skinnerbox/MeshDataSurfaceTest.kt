package borg.trikeshed.skinnerbox

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.WireHttpResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MeshDataSurfaceTest {
    private val tokenA = "a".repeat(64)
    private val tokenB = "b".repeat(64)
    private val readToken = "c".repeat(64)
    private fun headers(token: String = tokenA, id: String = "create-1", vararg rest: Pair<String, String>) =
        mapOf("apikey" to token, "idempotency-key" to id, "content-type" to "application/json") + rest
    private fun WireHttpResponse.text() = payloadBytes.decodeToString()

    @Test
    fun tenantCredentialsAndReadOnlyGrantPrecedePayloadParsing() = runBlocking<Unit> {
        var calls = 0
        val surface = MeshDataSurface(s_[MeshApiKey.fromToken("tenant.a", tokenA),
            MeshApiKey.fromToken("tenant.b", tokenB), MeshApiKey.fromToken("tenant.a", readToken, false)], { calls++; error("must not dispatch") })
        assertEquals(401, surface.handle("POST", "/rest/v1/rows", emptyMap(), byteArrayOf(-1))!!.status)
        assertEquals(401, surface.handle("GET", "/rest/v1/rows", headers(rest = arrayOf("authorization" to "Bearer $tokenB")), byteArrayOf())!!.status)
        assertEquals(403, surface.handle("POST", "/rest/v1/rows", headers(readToken), byteArrayOf(-1))!!.status)
        assertEquals(400, surface.handle("GET", "/rest/v1/rows", headers(rest = arrayOf("x-mesh-namespace" to "tenant.b")), byteArrayOf())!!.status)
        for (collection in MeshNode.reservedCollections) {
            assertEquals(403, surface.handle("POST", "/rest/v1/$collection", headers(), byteArrayOf())!!.status)
            assertEquals(403, surface.handle("POST", "/storage/v1/object/$collection/inject", headers(), byteArrayOf())!!.status)
        }
        assertEquals(0, calls)
    }

    @Test
    fun exactRowsRetryAndRevisionConflictTraversePersistence() = runBlocking<Unit> {
        val store = MeshPersistence.volatile(this, mapOf("gate" to setOf("tenant.a", "tenant.b")))
        val surface = MeshDataSurface(s_[MeshApiKey.fromToken("tenant.a", tokenA), MeshApiKey.fromToken("tenant.b", tokenB)],
            { store.execute("gate", it) })
        try {
            val body = "{\"id\":\"one\",\"money\":9007199254740993}".encodeToByteArray()
            val createHeaders = headers(rest = arrayOf("prefer" to "return=representation"))
            val created = surface.handle("POST", "/rest/v1/rows", createHeaders, body)!!
            assertEquals(201, created.status)
            assertContains(created.text(), "9007199254740993")
            val retry = surface.handle("POST", "/rest/v1/rows", createHeaders, body)!!
            assertEquals(created.headers["ETag"], retry.headers["ETag"])
            assertEquals(created.headers["X-Mesh-Sequence"], retry.headers["X-Mesh-Sequence"])
            val read = surface.handle("GET", "/rest/v1/rows?id=eq.one", headers(), byteArrayOf())!!
            assertEquals(200, read.status)
            assertContains(read.text(), "9007199254740993")
            assertEquals(404, surface.handle("GET", "/rest/v1/rows?id=eq.one", headers(tokenB), byteArrayOf())!!.status)
            val changed = "{\"id\":\"one\",\"money\":7}".encodeToByteArray()
            assertEquals(428, surface.handle("PUT", "/rest/v1/rows?id=eq.one", headers(id = "update-1"), changed)!!.status)
            val updateHeaders = headers(id = "update-1", rest = arrayOf("if-match" to created.headers.getValue("ETag")))
            assertEquals(200, surface.handle("PUT", "/rest/v1/rows?id=eq.one", updateHeaders, changed)!!.status)
            assertEquals(409, surface.handle("PUT", "/rest/v1/rows?id=eq.one",
                headers(id = "update-2", rest = arrayOf("if-match" to created.headers.getValue("ETag"))), changed)!!.status)
            val feed = surface.handle("GET", "${MeshDataSurface.CHANGES}?since=0&limit=1", headers(), byteArrayOf())!!
            assertEquals(200, feed.status)
            val page = TwinJson.parse(feed.payloadBytes) as Map<*, *>
            assertEquals(1, (page["changes"] as List<*>).size)
            assertEquals(true, page["more"])
        } finally { store.drain() }
    }

    @Test
    fun strictDataSubsetRejectsAmbiguousOrUnsupportedRequests() = runBlocking<Unit> {
        var calls = 0
        val surface = MeshDataSurface(s_[MeshApiKey.fromToken("tenant.a", tokenA)], { calls++; error("must not dispatch") })
        for (path in listOf("/rest/v1/rows?select=secret", "/rest/v1/rows?id=gt.one", "/rest/v1/rows?limit=bad",
            "/rest/v1/rows?limit=1&limit=2", "/rest/v1/rows?after=%GG", "/rest/v1/a%2fb",
            "/rest/v1/rows?or=(id.eq.one)", "/rest/v1/rows?id=eq.one&limit=2")) {
            assertEquals(400, surface.handle("GET", path, headers(), byteArrayOf())!!.status, path)
        }
        for (json in listOf("{\"id\":\"one\",\"id\":\"two\"}", "[]", "{\"id\":2}", "{\"id\":\"one\",\"n\":NaN}"))
            assertEquals(400, surface.handle("POST", "/rest/v1/rows", headers(), json.encodeToByteArray())!!.status, json)
        assertEquals(405, surface.handle("PATCH", "/rest/v1/rows?id=eq.one", headers(), "{}".encodeToByteArray())!!.status)
        assertEquals(400, surface.handle("POST", "/rest/v1/rows", headers(rest = arrayOf("prefer" to "resolution=merge-duplicates")),
            "{\"id\":\"one\"}".encodeToByteArray())!!.status)
        assertEquals(0, calls)
    }

    @Test
    fun malformedUtf8IsInputFailureWhileDispatchFailureRemainsUnavailable() = runBlocking<Unit> {
        var calls = 0
        val surface = MeshDataSurface(s_[MeshApiKey.fromToken("tenant.a", tokenA)], {
            calls++
            error("Persistence unavailable")
        })
        for (path in listOf("/rest/v1/rows?after=%FF", "/rest/v1/%FF"))
            assertEquals(400, surface.handle("GET", path, headers(), byteArrayOf())!!.status, path)
        val invalid = "{\"id\":\"one\",\"value\":\"".encodeToByteArray() + byteArrayOf(-1) + "\"}".encodeToByteArray()
        assertEquals(400, surface.handle("POST", "/rest/v1/rows", headers(), invalid)!!.status)
        assertEquals(0, calls)
        assertEquals(503, surface.handle("POST", "/rest/v1/rows", headers(), "{\"id\":\"one\"}".encodeToByteArray())!!.status)
        assertEquals(1, calls)
    }

    @Test
    fun objectsRetainBinaryBytesAndDeletionNeedsCurrentRevision() = runBlocking<Unit> {
        val store = MeshPersistence.volatile(this, mapOf("gate" to setOf("tenant.a")))
        val surface = MeshDataSurface(s_[MeshApiKey.fromToken("tenant.a", tokenA)], { store.execute("gate", it) })
        try {
            val bytes = ByteArray(8193) { (it % 251).toByte() }
            val path = "/storage/v1/object/assets/blob"
            val created = surface.handle("POST", path, headers(id = "object-1"), bytes)!!
            assertEquals(201, created.status)
            val loaded = surface.handle("GET", path, headers(), byteArrayOf())!!
            assertEquals(200, loaded.status)
            assertContentEquals(bytes, loaded.payloadBytes)
            assertEquals(ContentId.of(bytes).value, loaded.headers["X-Content-Id"])
            assertEquals(428, surface.handle("DELETE", path, headers(id = "delete-1"), byteArrayOf())!!.status)
            assertEquals(204, surface.handle("DELETE", path, headers(id = "delete-1", rest = arrayOf("if-match" to created.headers.getValue("ETag"))), byteArrayOf())!!.status)
            assertEquals(404, surface.handle("GET", path, headers(), byteArrayOf())!!.status)
        } finally { store.drain() }
    }

    @Test
    fun twinsUseTenantSubmissionCanonicalValuesAndPinnedContinuation() = runBlocking<Unit> {
        val persistence = MeshPersistence.volatile(this, mapOf("gate" to setOf("tenant.a", "tenant.b")))
        val twins = MeshTwinElement.open(this, persistence,
            mapOf("gate" to TwinGrant("gate", TwinSource.entries.toSet(), s_[TwinScope("*", "")])),
            s_["tenant.a", "tenant.b"], "gate")
        val surface = MeshDataSurface(s_[MeshApiKey.fromToken("tenant.a", tokenA), MeshApiKey.fromToken("tenant.b", tokenB),
            MeshApiKey.fromToken("tenant.a", readToken, false)], { twins.execute("gate", it) })
        fun observation(time: Long) = TwinObservation.create(TwinKey("user", "alice+one"), TwinSource.MEASURED, "fixture", time,
            mapOf("exact" to 9007199254740993L, "references" to listOf(
                mapOf("kind" to "user", "key" to "missing1"), mapOf("kind" to "user", "key" to "missing2"))))
        fun escaped(value: String) = value.encodeToByteArray().joinToString("") { "%" + (it.toInt() and 255).toString(16).padStart(2, '0') }
        try {
            val first = observation(1).bytes()
            assertEquals(428, surface.handle("POST", MeshDataSurface.TWINS, headers(), first)!!.status)
            assertEquals(403, surface.handle("POST", MeshDataSurface.TWINS, headers(readToken), first)!!.status)
            val h = headers(rest = arrayOf("x-request-id" to "twin-1"))
            assertEquals(400, surface.handle("POST", MeshDataSurface.TWINS, h, first + ' '.code.toByte())!!.status)
            val created = surface.handle("POST", MeshDataSurface.TWINS, h, first)!!
            assertEquals(201, created.status)
            val receipt = TwinJson.parse(created.payloadBytes) as Map<*, *>
            assertEquals(1L, receipt["added"])
            assertFalse("event" in receipt)
            assertEquals(created.text(), surface.handle("POST", MeshDataSurface.TWINS, h, first)!!.text())
            val path = "${MeshDataSurface.TWINS}/user/alice+one"
            assertContentEquals(first, surface.handle("GET", path, headers(), byteArrayOf())!!.payloadBytes)
            assertEquals(404, surface.handle("GET", path, headers(tokenB), byteArrayOf())!!.status)
            val second = observation(2).bytes()
            assertEquals(201, surface.handle("POST", MeshDataSurface.TWINS,
                headers(rest = arrayOf("x-request-id" to "twin-2")), second)!!.status)
            val page = surface.handle("GET", "${MeshDataSurface.TWIN_RESIDUAL}?limit=1", headers(), byteArrayOf())!!
            assertEquals(200, page.status)
            val residual = TwinJson.parse(page.payloadBytes) as Map<*, *>
            assertEquals(1, (residual["observations"] as List<*>).size)
            assertContains(page.text(), "9007199254740993")
            val after = escaped(residual["after"] as String)
            val savepoint = escaped(residual["savepoint"] as String)
            assertEquals(400, surface.handle("GET", "${MeshDataSurface.TWIN_RESIDUAL}?after=$after", headers(), byteArrayOf())!!.status)
            val last = surface.handle("GET", "${MeshDataSurface.TWIN_RESIDUAL}?limit=1&after=$after&savepoint=$savepoint", headers(), byteArrayOf())!!
            assertNull((TwinJson.parse(last.payloadBytes) as Map<*, *>)["after"])
            val diagnostics = surface.handle("GET", "${MeshDataSurface.TWIN_REFERENCES}?limit=1", headers(), byteArrayOf())!!
            val references = TwinJson.parse(diagnostics.payloadBytes) as Map<*, *>
            assertEquals(1, (references["issues"] as List<*>).size)
            val cursor = escaped(TwinJson.encode(references["after"]).decodeToString())
            val next = surface.handle("GET", "${MeshDataSurface.TWIN_REFERENCES}?limit=1&after=$cursor&savepoint=$savepoint", headers(), byteArrayOf())!!
            assertEquals(200, next.status)
            assertNull((TwinJson.parse(next.payloadBytes) as Map<*, *>)["after"])
        } finally { twins.drain(); persistence.drain() }
    }

    @Test
    fun twinProjectionRequiresOneExplicitBoundedCoordinate() = runBlocking<Unit> {
        var calls = 0
        val savepoint = ContentId.of(byteArrayOf(1)).value
        val surface = MeshDataSurface(s_[MeshApiKey.fromToken("tenant.a", readToken, false)], { task ->
            calls++
            assertEquals("tenant.a", task.namespace)
            assertEquals("twin.project", task.verb)
            assertEquals(mapOf("model" to "llama", "hardware" to "a100", "continent" to "EU"), MeshPersistenceCodec.decode(task.payload))
            MeshPersistenceCodec.encode(mapOf("savepoint" to savepoint, "problems" to emptyList<String>()))
        })
        assertEquals(400, surface.handle("GET", MeshDataSurface.TWIN_PROJECT, headers(readToken), byteArrayOf())!!.status)
        assertEquals(400, surface.handle("GET", "${MeshDataSurface.TWIN_PROJECT}?model=llama&hardware=a100&continent=EU&extra=x", headers(readToken), byteArrayOf())!!.status)
        val reply = surface.handle("GET", "${MeshDataSurface.TWIN_PROJECT}?model=llama&hardware=a100&continent=EU", headers(readToken), byteArrayOf())!!
        assertEquals(200, reply.status)
        assertEquals(savepoint, reply.headers["X-Mesh-Savepoint"])
        assertEquals(1, calls)
    }

    @Test
    fun priorCustodyFailureReportsThatCurrentMutationWasNotAdmitted() = runBlocking<Unit> {
        val surface = MeshDataSurface(s_[MeshApiKey.fromToken("tenant.a", tokenA)], {
            throw MeshPriorCustodyPending("older-request", ContentId.of(byteArrayOf(1)), 2)
        })
        val reply = surface.handle("POST", "/rest/v1/rows", headers(), "{\"id\":\"one\"}".encodeToByteArray())!!
        assertEquals(503, reply.status)
        assertContains(reply.text(), "prior_custody_pending")
        assertContains(reply.text(), "this request was not admitted")
        assertFalse(reply.text().contains("older-request"))
    }

    @Test
    fun corsIsExplicitAndPreflightCannotInvokeTasks() = runBlocking<Unit> {
        var calls = 0
        val surface = MeshDataSurface(s_[MeshApiKey.fromToken("tenant.a", tokenA)], { calls++; error("must not dispatch") },
            setOf("https://app.example"))
        val preflight = mapOf("origin" to "https://app.example", "access-control-request-method" to "POST",
            "access-control-request-headers" to "apikey, content-type, idempotency-key")
        val allowed = surface.handle("OPTIONS", "/rest/v1/rows", preflight, byteArrayOf())!!
        assertEquals(204, allowed.status)
        assertEquals("https://app.example", allowed.headers["Access-Control-Allow-Origin"])
        assertEquals(403, surface.handle("GET", "/rest/v1/rows", headers(rest = arrayOf("origin" to "https://evil.example")), byteArrayOf())!!.status)
        assertEquals(400, surface.handle("OPTIONS", "/rest/v1/rows", preflight + ("access-control-request-headers" to "x-unhandled"), byteArrayOf())!!.status)
        assertEquals(0, calls)
    }
}
