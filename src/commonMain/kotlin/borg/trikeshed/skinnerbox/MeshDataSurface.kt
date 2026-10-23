package borg.trikeshed.skinnerbox

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.WireHttpResponse
import kotlinx.coroutines.CancellationException

/** A tenant grant stores only the digest of a deployment-provided credential. */
data class MeshApiKey(val namespace: String, val tokenHash: ContentId, val write: Boolean = true) {
    init { meshSubnet(namespace) }

    companion object {
        fun fromToken(namespace: String, token: String, write: Boolean = true): MeshApiKey {
            require(token.length in 32..512 && token.all { it.code in 33..126 }) { "Mesh API credential shape" }
            return MeshApiKey(namespace, ContentId.of(token.encodeToByteArray()), write)
        }
    }
}

/**
 * Tenant data over the peer task path. Single-row PostgREST-shaped CRUD, opaque objects,
 * and an explicit sequence-polling extension; not PostgreSQL/RLS or a Realtime websocket server.
 */
class MeshDataSurface(
    grants: Series<MeshApiKey>,
    private val submit: suspend (MeshTask) -> ByteArray,
    allowedOrigins: Set<String> = emptySet(),
) {
    private val keys = List(grants.size) { grants[it] }
    private val origins = allowedOrigins.toSet()

    init {
        require(keys.size in 1..1024 && keys.map { it.tokenHash }.toSet().size == keys.size) { "Mesh API grants" }
        require(origins.size <= 32 && origins.all { origin ->
            origin.length <= 512 && origin.all { it.code in 33..126 } &&
                (origin.startsWith("https://") || origin.startsWith("http://127.0.0.1:") || origin.startsWith("http://localhost:")) &&
                origin.substringAfter("://").none { it in "/?#@" }
        }) { "Mesh API CORS origins" }
    }

    suspend fun handle(method: String, target: String, headers: Map<String, String>, body: ByteArray): WireHttpResponse? {
        val path = target.substringBefore('?')
        if (!path.startsWith("/rest/v1/") && !path.startsWith("/storage/v1/object/") && path != CHANGES &&
            path != TWINS && !path.startsWith("$TWINS/") && path !in TWIN_READS) return null
        val h = linkedMapOf<String, String>()
        for ((key, value) in headers) {
            if (h.put(key.lowercase(), value.trim()) != null) return failure(400, "duplicate_header")
        }
        val origin = h["origin"]
        if (origin != null && origin !in origins) return failure(403, "origin_not_allowed")
        if (method == "OPTIONS") {
            if (origin == null || h["access-control-request-method"] !in METHODS) return failure(400, "invalid_preflight")
            val requested = h["access-control-request-headers"].orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (requested.any { it !in CORS_HEADERS }) return failure(400, "unsupported_header")
            return cors(WireHttpResponse(204, "", headers = mapOf(
                "Access-Control-Allow-Methods" to METHODS.joinToString(", "),
                "Access-Control-Allow-Headers" to CORS_HEADERS.joinToString(", "),
            )), origin)
        }
        val response = try {
            if (method !in METHODS) throw MeshHttpException(405, "HTTP method")
            require(body.size <= MeshTask.MAX_PAYLOAD_BYTES) { "API body bound" }
            val grant = authenticate(h)
            val mutation = method !in setOf("GET", "HEAD")
            if (mutation && !grant.write) throw MeshHttpException(403, "Read-only credential")
            require(h["x-mesh-namespace"] == null || h["x-mesh-namespace"] == grant.namespace) { "Credential tenant mismatch" }
            if (!mutation) require(body.isEmpty()) { "Read body unsupported" }
            require("range" !in h && "accept-profile" !in h && "content-profile" !in h) { "Unsupported query headers" }
            val query = query(target)
            when {
                path == CHANGES -> changes(method, grant, query)
                path == TWINS || path.startsWith("$TWINS/") || path in TWIN_READS -> twins(method, path, grant, query, h, body)
                path.startsWith("/storage/v1/object/") -> objects(method, path, grant, query, h, body)
                else -> rows(method, path, grant, query, h, body)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: MeshCustodyPending) {
            WireHttpResponse(503, "{\"code\":\"custody_pending\",\"message\":\"Local commit retained; retry the same request id.\"}",
                headers = mapOf("Retry-After" to "1"))
        } catch (_: MeshPriorCustodyPending) {
            WireHttpResponse(503, "{\"code\":\"prior_custody_pending\",\"message\":\"Previous commit awaiting custody; this request was not admitted. Retry the same request id after repair.\"}",
                headers = mapOf("Retry-After" to "1"))
        } catch (cause: MeshPersistenceException) {
            when (cause.reason) {
                MeshPersistenceError.NOT_FOUND -> failure(404, "not_found")
                MeshPersistenceError.UNAUTHORIZED -> failure(403, "forbidden")
                MeshPersistenceError.CONFLICT -> failure(409, "revision_or_request_conflict")
                MeshPersistenceError.CAPACITY -> failure(413, "limit")
                MeshPersistenceError.INVALID -> failure(400, "invalid_request")
                MeshPersistenceError.CLOSED, MeshPersistenceError.IO -> failure(503, "unavailable")
            }
        } catch (cause: MeshHttpException) {
            failure(cause.status, "invalid_request")
        } catch (_: IllegalArgumentException) {
            failure(400, "invalid_request")
        } catch (_: Exception) {
            failure(503, "unavailable")
        }
        return cors(response.copy(headers = response.headers + ("Cache-Control" to "no-store")), origin)
    }

    private fun authenticate(headers: Map<String, String>): MeshApiKey {
        val authorization = headers["authorization"]
        val bearer = authorization?.let {
            if (!it.startsWith("Bearer ", ignoreCase = true)) throw MeshHttpException(401, "Credential required")
            it.substring(7)
        }
        val apiKey = headers["apikey"]
        if (bearer != null && apiKey != null && bearer != apiKey) throw MeshHttpException(401, "Conflicting credentials")
        val token = bearer ?: apiKey ?: throw MeshHttpException(401, "Credential required")
        if (token.length !in 32..512 || token.any { it.code !in 33..126 }) throw MeshHttpException(401, "Credential required")
        val hash = ContentId.of(token.encodeToByteArray())
        return keys.firstOrNull { it.tokenHash == hash } ?: throw MeshHttpException(401, "Credential required")
    }

    private suspend fun rows(method: String, path: String, grant: MeshApiKey, query: Map<String, String>,
        headers: Map<String, String>, body: ByteArray): WireHttpResponse {
        val collection = identifier(decode(path.removePrefix("/rest/v1/")))
        if (collection in MeshNode.reservedCollections) throw MeshHttpException(403, "Reserved mesh collection")
        require(query.keys.all { it in setOf("id", "limit", "after", "select") }) { "Unsupported row filter" }
        require(query["select"] == null || query["select"] == "*") { "Only full rows are selected" }
        val id = query["id"]?.let { require(it.startsWith("eq.")); identifier(it.removePrefix("eq.")) }
        val representation = prefer(headers)
        if (method == "GET" || method == "HEAD") {
            if (id != null) {
                require(query["limit"] == null && query["after"] == null)
                val reply = call(grant, "row.get", mapOf("collection" to collection, "key" to id))
                val value = rowValue(reply)
                val one = headers["accept"] == "application/vnd.pgrst.object+json"
                return json(if (one) value else listOf(value), headers = revisionHeaders(reply))
            }
            require(headers["accept"] != "application/vnd.pgrst.object+json") { "Object response requires exact id" }
            val reply = call(grant, "row.list", mapOf("collection" to collection,
                "after" to query["after"]?.let(::identifier), "limit" to limit(query)))
            val rows = (reply["rows"] as? List<*>) ?: error("Mesh row list")
            val values = rows.map { rowValue(stringMap(it)) }
            return json(values, headers = pageHeaders(reply))
        }
        require(query["limit"] == null && query["after"] == null)
        val request = requestId(headers)
        val expected = expectedRevision(method, headers)
        val fields = linkedMapOf<String, Any?>("requestId" to request, "collection" to collection)
        if (method == "DELETE") {
            require(id != null && body.isEmpty())
            fields["key"] = id
            fields["expectedRevision"] = expected
            val reply = call(grant, "row.delete", fields)
            return WireHttpResponse(204, "", headers = revisionHeaders(reply))
        }
        if (method !in setOf("POST", "PUT")) throw MeshHttpException(405, "Use full-row PUT with If-Match")
        require(headers["content-type"]?.substringBefore(';')?.lowercase() == "application/json") { "JSON required" }
        val value = stringMap(inputJson(body, ROW_BYTES))
        val key = identifier(value["id"] as? String ?: bad("String id required"))
        require(id == null || id == key) { "Row id mismatch" }
        fields["key"] = key
        fields["expectedRevision"] = expected
        fields["value"] = TwinJson.encode(value, ROW_BYTES)
        val reply = call(grant, "row.put", fields)
        val status = if (method == "POST") 201 else 200
        return if (representation) json(listOf(value), status, revisionHeaders(reply))
            else WireHttpResponse(status, "", headers = revisionHeaders(reply))
    }

    private suspend fun objects(method: String, path: String, grant: MeshApiKey, query: Map<String, String>,
        headers: Map<String, String>, body: ByteArray): WireHttpResponse {
        require(query.isEmpty()) { "Object query unsupported" }
        val parts = path.removePrefix("/storage/v1/object/").split('/')
        require(parts.size == 2)
        val collection = identifier(decode(parts[0]))
        if (collection in MeshNode.reservedCollections) throw MeshHttpException(403, "Reserved mesh collection")
        val key = identifier(decode(parts[1]))
        val fields = linkedMapOf<String, Any?>("collection" to collection, "key" to key)
        if (method == "GET" || method == "HEAD") {
            val reply = call(grant, "object.get", fields)
            val value = reply["value"] as? ByteArray ?: error("Mesh object")
            require(value.size <= OBJECT_BYTES)
            return WireHttpResponse(200, "", "application/octet-stream", value, revisionHeaders(reply))
        }
        require(method in setOf("POST", "PUT", "DELETE"))
        require(body.size <= OBJECT_BYTES)
        fields["requestId"] = requestId(headers)
        fields["expectedRevision"] = expectedRevision(method, headers)
        if (method == "DELETE") {
            require(body.isEmpty())
            val reply = call(grant, "object.delete", fields)
            return WireHttpResponse(204, "", headers = revisionHeaders(reply))
        }
        require(headers["x-upsert"] == null || headers["x-upsert"] == "false") { "Use revision-aware PUT" }
        fields["value"] = body
        val reply = call(grant, "object.put", fields)
        return json(mapOf("Key" to "$collection/$key", "revision" to reply["revision"]),
            if (method == "POST") 201 else 200, revisionHeaders(reply))
    }

    private suspend fun changes(method: String, grant: MeshApiKey, query: Map<String, String>): WireHttpResponse {
        require(method in setOf("GET", "HEAD") && query.keys.all { it in setOf("since", "limit") })
        val since = query["since"]?.toLongOrNull() ?: if (query["since"] == null) 0L else bad("Changes cursor")
        require(since >= 0)
        return json(call(grant, "changes", mapOf("since" to since, "limit" to limit(query))))
    }

    private suspend fun twins(method: String, path: String, grant: MeshApiKey, query: Map<String, String>,
        headers: Map<String, String>, body: ByteArray): WireHttpResponse {
        if (path == TWINS) {
            if (method != "POST") throw MeshHttpException(405, "Twin observation POST required")
            require(query.isEmpty() && "if-match" !in headers && "if-none-match" !in headers && "prefer" !in headers)
            require(headers["content-type"]?.substringBefore(';')?.lowercase() == "application/json")
            if (body.size > TwinObservation.MAX_BYTES) throw MeshHttpException(413, "Twin observation limit")
            val observation = try { TwinObservation.parse(body) }
            catch (_: Exception) { throw MeshHttpException(400, "Canonical twin observation required") }
            val request = identifier(headers["x-request-id"] ?: throw MeshHttpException(428, "X-Request-ID required"))
            val reply = call(grant, "twin.merge", mapOf("requestId" to request, "observations" to listOf(observation.bytes())))
            // The durable event is a peer custody packet, not an embedded byte array in the JSON API.
            val receipt = reply.filterKeys { it in setOf("requestId", "revision", "sequence", "contentId", "deleted", "durable", "added", "savepoint") }
            return json(receipt, 201, revisionHeaders(reply) + twinHeaders(reply))
        }
        if (method !in setOf("GET", "HEAD")) throw MeshHttpException(405, "Twin read required")
        if (path.startsWith("$TWINS/")) {
            require(query.isEmpty())
            val parts = path.removePrefix("$TWINS/").split('/', limit = 2)
            require(parts.size == 2)
            val key = TwinKey(decode(parts[0]), decode(parts[1]))
            val reply = call(grant, "twin.get", mapOf("kind" to key.kind, "key" to key.key))
            val observation = twinValue(reply["observation"])
            require(ContentId(reply["contentId"] as? String ?: error("Twin content identity")) == observation.id)
            return WireHttpResponse(200, "", bytes = observation.bytes(),
                headers = twinHeaders(reply) + ("ETag" to "\"${observation.id.value}\""))
        }
        val fields = linkedMapOf<String, Any?>()
        when (path) {
            TWIN_RESIDUAL, TWIN_REFERENCES -> {
                require(query.keys.all { it in setOf("limit", "after", "savepoint") })
                fields["limit"] = limit(query)
                query["savepoint"]?.let { fields["savepoint"] = ContentId(it).value }
                query["after"]?.let { after ->
                    require("savepoint" in fields) { "Twin continuation requires its savepoint" }
                    fields["after"] = if (path == TWIN_RESIDUAL) ContentId(after).value else {
                        val cursor = stringMap(inputJson(after.encodeToByteArray(), 4096))
                        require(cursor.keys == setOf("kind", "key", "offset"))
                        val key = TwinKey(cursor["kind"] as? String ?: bad("Twin cursor kind"), cursor["key"] as? String ?: bad("Twin cursor key"))
                        val offset = cursor["offset"] as? Long ?: bad("Twin cursor offset")
                        require(offset in 0..TwinJson.MAX_VALUES.toLong())
                        mapOf("kind" to key.kind, "key" to key.key, "offset" to offset)
                    }
                }
                if (path == TWIN_RESIDUAL) fields["known"] = emptyList<String>()
            }
            TWIN_PROJECT -> {
                require(query.keys == setOf("model", "hardware", "continent"))
                for ((name, value) in query) {
                    require(value.length in 1..256 && value.none { it.code < 32 || it.code == 127 })
                    fields[name] = value
                }
            }
            else -> throw MeshHttpException(404, "Twin route")
        }
        val verb = when (path) { TWIN_RESIDUAL -> "twin.residual"; TWIN_REFERENCES -> "twin.references"; else -> "twin.project" }
        val reply = call(grant, verb, fields)
        if (path != TWIN_RESIDUAL) return json(reply, headers = twinHeaders(reply))
        val raw = reply["observations"] as? List<*> ?: error("Twin residual observations")
        require(raw.size <= 64)
        var total = 0
        val observations = raw.map {
            val value = twinValue(it)
            total += value.byteSize
            require(total <= MeshPersistenceCodec.OBJECT_BYTES)
            TwinJson.parse(value.bytes(), TwinObservation.MAX_BYTES)
        }
        val after = reply["after"]?.let { ContentId(it as? String ?: error("Twin residual cursor")).value }
        return json(mapOf("observations" to observations, "after" to after, "savepoint" to reply["savepoint"]),
            headers = twinHeaders(reply))
    }

    private fun twinValue(value: Any?): TwinObservation =
        TwinObservation.parse(value as? ByteArray ?: error("Twin observation bytes"))

    private fun inputJson(bytes: ByteArray, limit: Int): Any? = try { TwinJson.parse(bytes, limit) }
    catch (_: Exception) { throw MeshHttpException(400, "Invalid JSON encoding or grammar") }

    private fun twinHeaders(reply: Map<String, Any?>): Map<String, String> = mapOf(
        "X-Mesh-Savepoint" to ContentId(reply["savepoint"] as? String ?: error("Twin savepoint")).value)

    private suspend fun call(grant: MeshApiKey, verb: String, fields: Map<String, Any?>): Map<String, Any?> {
        val response = submit(MeshTask(grant.namespace, verb, CanonicalCbor.encodeMap(fields)))
        require(response.size <= MeshTask.MAX_PAYLOAD_BYTES) { "Mesh data response limit" }
        return MeshPersistenceCodec.decode(response)
    }

    private fun rowValue(reply: Map<String, Any?>): Map<String, Any?> =
        stringMap(TwinJson.parse(reply["value"] as? ByteArray ?: error("Mesh row"), ROW_BYTES))

    private fun revisionHeaders(reply: Map<String, Any?>): Map<String, String> {
        val revision = reply["revision"] as? String ?: error("Mesh revision")
        ContentId(revision)
        val sequence = reply["sequence"] as? Long ?: error("Mesh sequence")
        require(sequence >= 0)
        val values = linkedMapOf("ETag" to "\"$revision\"", "X-Mesh-Sequence" to sequence.toString())
        (reply["contentId"] as? String)?.let { ContentId(it); values["X-Content-Id"] = it }
        return values
    }

    private fun pageHeaders(reply: Map<String, Any?>): Map<String, String> {
        val values = linkedMapOf("X-Mesh-More" to (reply["more"] as? Boolean ?: error("Mesh cursor")).toString())
        (reply["after"] as? String)?.let { values["X-Mesh-After"] = identifier(it) }
        return values
    }

    private fun expectedRevision(method: String, headers: Map<String, String>): String? {
        if (method == "POST") {
            require(headers["if-match"] == null && headers["if-none-match"].let { it == null || it == "*" })
            return null
        }
        require(headers["if-none-match"] == null)
        val supplied = headers["if-match"] ?: throw MeshHttpException(428, "If-Match required")
        val revision = if (supplied.startsWith('"') && supplied.endsWith('"')) supplied.drop(1).dropLast(1) else supplied
        ContentId(revision)
        return revision
    }

    private fun prefer(headers: Map<String, String>): Boolean = when (headers["prefer"]) {
        null, "return=minimal" -> false
        "return=representation" -> true
        else -> throw MeshHttpException(400, "Unsupported preference")
    }

    private fun requestId(headers: Map<String, String>): String =
        identifier(headers["idempotency-key"] ?: throw MeshHttpException(428, "Idempotency-Key required"))

    private fun identifier(value: String): String {
        require(value.length in 1..128 && value.first().let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } &&
            value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }) { "Data identifier" }
        return value
    }

    private fun limit(query: Map<String, String>): Long {
        val value = query["limit"]?.toLongOrNull() ?: if (query["limit"] == null) 32L else bad("Page limit")
        require(value in 1..64)
        return value
    }

    private fun query(target: String): Map<String, String> {
        require(target.length <= 4096 && '#' !in target)
        val query = target.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (pair in query.split('&')) {
            require('=' in pair && result.size < 8)
            val key = decode(pair.substringBefore('='), plusAsSpace = true)
            require(result.put(key, decode(pair.substringAfter('='), plusAsSpace = true)) == null) { "Duplicate query key" }
        }
        return result
    }

    private fun decode(text: String, plusAsSpace: Boolean = false): String {
        require(text.length <= 4096)
        val bytes = ByteArray(text.length)
        var count = 0
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            bytes[count++] = when (char) {
                '%' -> {
                    require(index + 1 < text.length)
                    val high = text[index++].digitToIntOrNull(16) ?: bad("URL escape")
                    val low = text[index++].digitToIntOrNull(16) ?: bad("URL escape")
                    ((high shl 4) or low).toByte()
                }
                '+' -> if (plusAsSpace) 32.toByte() else '+'.code.toByte()
                else -> { require(char.code in 33..126); char.code.toByte() }
            }
        }
        return try { bytes.decodeToString(0, count, throwOnInvalidSequence = true) }
        catch (_: Exception) { throw MeshHttpException(400, "Invalid URL encoding") }
    }

    private fun stringMap(value: Any?): Map<String, Any?> {
        val map = value as? Map<*, *> ?: throw IllegalArgumentException("JSON object required")
        require(map.keys.all { it is String })
        @Suppress("UNCHECKED_CAST") return map as Map<String, Any?>
    }

    private fun json(value: Any?, status: Int = 200, headers: Map<String, String> = emptyMap()): WireHttpResponse {
        val bytes = try { TwinJson.encode(value) }
        catch (_: IllegalArgumentException) { throw MeshHttpException(413, "Reduce page size") }
        return WireHttpResponse(status, "", bytes = bytes, headers = headers)
    }

    private fun failure(status: Int, code: String): WireHttpResponse = WireHttpResponse(status, "{\"code\":\"$code\"}")

    private fun bad(message: String): Nothing = throw IllegalArgumentException(message)

    private fun cors(response: WireHttpResponse, origin: String?): WireHttpResponse = if (origin == null) response else
        response.copy(headers = response.headers + mapOf("Access-Control-Allow-Origin" to origin, "Vary" to "Origin",
            "Access-Control-Expose-Headers" to "ETag, X-Mesh-Sequence, X-Content-Id, X-Mesh-After, X-Mesh-More, X-Mesh-Savepoint"))

    companion object {
        const val CHANGES: String = "/v1/skinnerbox/changes"
        const val TWINS: String = "/v1/skinnerbox/twins"
        const val TWIN_RESIDUAL: String = "/v1/skinnerbox/twin-residual"
        const val TWIN_REFERENCES: String = "/v1/skinnerbox/twin-references"
        const val TWIN_PROJECT: String = "/v1/skinnerbox/twin-project"
        const val ROW_BYTES: Int = 16 * 1024
        const val OBJECT_BYTES: Int = 64 * 1024
        private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "DELETE")
        private val CORS_HEADERS = setOf("authorization", "apikey", "content-type", "prefer", "if-match", "if-none-match",
            "idempotency-key", "x-request-id", "x-mesh-namespace", "x-client-info")
        private val TWIN_READS = setOf(TWIN_RESIDUAL, TWIN_REFERENCES, TWIN_PROJECT)
    }
}
