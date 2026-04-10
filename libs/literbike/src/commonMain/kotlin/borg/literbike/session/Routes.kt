/**
 * Port of /Users/jim/work/literbike/src/session/routes.rs
 *
 * HTTP route handlers and session manager for session endpoints.
 *
 * NOTE: The Rust version uses `axum` (a Rust web framework). This translation
 * preserves the structure, handler logic, and routing patterns. The actual
 * framework-specific types (axum Router, extractors, etc.) are replaced with
 * idiomatic Kotlin equivalents. If using Ktor, these handlers can be adapted
 * to Ktor's routing DSL.
 */
package borg.literbike.session

import java.util.UUID

// ============================================================================
// SessionManager
// ============================================================================

/**
 * Mirrors Rust struct: `#[derive(Clone)] pub struct SessionManager`
 */
class SessionManager {
    val sessions: MutableMap<String, SessionChannel> = mutableMapOf()

    companion object {
        fun create(): SessionManager = SessionManager()
    }

    fun copy(): SessionManager = SessionManager().also {
        it.sessions.putAll(sessions)
    }
}

// ============================================================================
// Request/Response Types
// ============================================================================

/**
 * Mirrors Rust struct: `#[derive(Deserialize)] pub struct CreateSessionBody`
 */
data class CreateSessionBody(
    val sessionId: String? = null,
)

/**
 * Mirrors Rust struct: `#[derive(Deserialize)] pub struct RecordTurnBody`
 */
data class RecordTurnBody(
    val role: String,
    val content: String,
)

/**
 * Mirrors Rust struct: `#[derive(Deserialize)] pub struct PatchFeedQuery`
 */
data class PatchFeedQuery(
    val since: String? = null,
)

// ============================================================================
// Response Helpers
// ============================================================================

/**
 * Mirrors Rust `(StatusCode, Json(...))` return pattern.
 */
data class HttpResponse(
    val status: Int,
    val body: Map<String, Any?>,
) {
    companion object {
        const val OK = 200
        const val NOT_FOUND = 404
        const val INTERNAL_SERVER_ERROR = 500
    }

    fun okResponse(body: Map<String, Any?>): HttpResponse =
        HttpResponse(OK, body)

    fun notFound(error: String): HttpResponse =
        HttpResponse(NOT_FOUND, mapOf("error" to error, "ok" to false))

    fun internalError(error: String): HttpResponse =
        HttpResponse(INTERNAL_SERVER_ERROR, mapOf("error" to error, "ok" to false))
}

// ============================================================================
// Handlers
// ============================================================================

/**
 * Mirrors Rust fn: `pub async fn create_session(State(mgr): State<SessionManager>, body: Option<Json<CreateSessionBody>>) -> impl IntoResponse`
 */
fun createSession(
    mgr: SessionManager,
    body: CreateSessionBody?,
): HttpResponse {
    val sessionId = body?.sessionId ?: UUID.randomUUID().toString()

    return openChannel(sessionId, InMemoryPijulBackend()).fold(
        onSuccess = { channel ->
            mgr.sessions[sessionId] = channel
            HttpResponse(
                status = HttpResponse.OK,
                body = mapOf("session_id" to sessionId, "ok" to true),
            )
        },
        onFailure = { e ->
            HttpResponse(
                status = HttpResponse.INTERNAL_SERVER_ERROR,
                body = mapOf("error" to (e.message ?: "unknown error"), "ok" to false),
            )
        },
    )
}

/**
 * Mirrors Rust fn: `pub async fn record_turn_handler(Path(id), State(mgr), Json(body)) -> impl IntoResponse`
 */
fun recordTurnHandler(
    id: String,
    mgr: SessionManager,
    body: RecordTurnBody,
): HttpResponse {
    val channel = mgr.sessions[id]
        ?: return HttpResponse(
            status = HttpResponse.NOT_FOUND,
            body = mapOf("error" to "session not found", "ok" to false),
        )

    return recordTurn(channel, body.role, body.content).fold(
        onSuccess = { hash ->
            HttpResponse(
                status = HttpResponse.OK,
                body = mapOf("hash" to hash, "ok" to true),
            )
        },
        onFailure = { e ->
            HttpResponse(
                status = HttpResponse.INTERNAL_SERVER_ERROR,
                body = mapOf("error" to (e.message ?: "unknown error"), "ok" to false),
            )
        },
    )
}

/**
 * Mirrors Rust fn: `pub async fn get_patches_handler(Path(id), Query(query), State(mgr)) -> impl IntoResponse`
 */
fun getPatchesHandler(
    id: String,
    mgr: SessionManager,
    query: PatchFeedQuery,
): HttpResponse {
    val channel = mgr.sessions[id]
        ?: return HttpResponse(
            status = HttpResponse.NOT_FOUND,
            body = mapOf("error" to "session not found", "ok" to false),
        )

    val since = query.since
    return patchFeed(channel, since).fold(
        onSuccess = { patches ->
            HttpResponse(
                status = HttpResponse.OK,
                body = mapOf("patches" to patches, "session_id" to id),
            )
        },
        onFailure = { e ->
            HttpResponse(
                status = HttpResponse.INTERNAL_SERVER_ERROR,
                body = mapOf("error" to (e.message ?: "unknown error"), "ok" to false),
            )
        },
    )
}

/**
 * Mirrors Rust fn: `pub async fn revert_turn_handler(Path((id, hash)), State(mgr)) -> impl IntoResponse`
 */
fun revertTurnHandler(
    id: String,
    hash: String,
    mgr: SessionManager,
): HttpResponse {
    val channel = mgr.sessions[id]
        ?: return HttpResponse(
            status = HttpResponse.NOT_FOUND,
            body = mapOf("error" to "session not found", "ok" to false),
        )

    return revertTurn(channel, hash).fold(
        onSuccess = {
            HttpResponse(
                status = HttpResponse.OK,
                body = mapOf("ok" to true),
            )
        },
        onFailure = { e ->
            HttpResponse(
                status = HttpResponse.INTERNAL_SERVER_ERROR,
                body = mapOf("error" to (e.message ?: "unknown error"), "ok" to false),
            )
        },
    )
}

// ============================================================================
// Router Definition
// ============================================================================

/**
 * Mirrors Rust fn: `pub fn session_router() -> Router<SessionManager>`
 *
 * Returns a list of route definitions that can be registered with any
 * Kotlin web framework (Ktor, http4k, etc.).
 */
data class RouteDefinition(
    val method: String,
    val path: String,
    val handler: (SessionManager, Map<String, String>) -> HttpResponse,
)

fun sessionRouter(): List<RouteDefinition> {
    val mgr = SessionManager()

    return listOf(
        RouteDefinition("POST", "/session") { _, _ ->
            createSession(mgr, null)
        },
        RouteDefinition("POST", "/session/{id}/turns") { _, params ->
            val id = params["id"] ?: return@RouteDefinition HttpResponse(
                status = HttpResponse.NOT_FOUND,
                body = mapOf("error" to "missing id", "ok" to false),
            )
            recordTurnHandler(id, mgr, RecordTurnBody(role = "", content = ""))
        },
        RouteDefinition("GET", "/session/{id}/patches") { _, params ->
            val id = params["id"] ?: return@RouteDefinition HttpResponse(
                status = HttpResponse.NOT_FOUND,
                body = mapOf("error" to "missing id", "ok" to false),
            )
            getPatchesHandler(id, mgr, PatchFeedQuery())
        },
        RouteDefinition("DELETE", "/session/{id}/turns/{hash}") { _, params ->
            val id = params["id"] ?: return@RouteDefinition HttpResponse(
                status = HttpResponse.NOT_FOUND,
                body = mapOf("error" to "missing id", "ok" to false),
            )
            val hash = params["hash"] ?: return@RouteDefinition HttpResponse(
                status = HttpResponse.NOT_FOUND,
                body = mapOf("error" to "missing hash", "ok" to false),
            )
            revertTurnHandler(id, hash, mgr)
        },
    )
}

// ============================================================================
// In-Memory Pijul Backend (Default Implementation)
// ============================================================================

/**
 * Default in-memory implementation of PijulBackend.
 * Stores patches as ordered entries with auto-generated hashes.
 */
class InMemoryPijulBackend : PijulBackend {
    private val patches = mutableListOf<PatchEntry>()
    private var nextSerial: Long = 0
    private var nextPatchId: Long = 0

    private data class PatchEntry(
        val serial: Long,
        val hash: HashRepresentation,
        val message: String,
    )

    override fun ensureMainChannel(): Result<Unit> = Result.success(Unit)

    override fun addFile(path: String, content: ByteArray): Result<Unit> = Result.success(Unit)

    override fun trackFile(path: String, offset: Long): Result<Unit> = Result.success(Unit)

    override fun recordChange(message: String): Result<String> {
        val patchId = nextPatchId++
        val hash = HashRepresentation("patch-$patchId".encodeToByteArray())
        val entry = PatchEntry(nextSerial++, hash, message)
        patches.add(entry)
        return Result.success(hash.toBase32())
    }

    override fun getPatchLog(fromSerial: Long): Result<List<HashEntry>> {
        val entries = patches
            .filter { it.serial >= fromSerial }
            .map { HashEntry(it.serial, it.hash, ByteArray(0)) }
        return Result.success(entries)
    }

    override fun unrecord(hash: String): Result<Unit> {
        val idx = patches.indexOfLast { it.hash.toBase32() == hash }
        if (idx >= 0) {
            patches.removeAt(idx)
        }
        return Result.success(Unit)
    }

    override fun parseHash(hashStr: String): Result<HashRepresentation> {
        return Result.success(HashRepresentation(hashStr.encodeToByteArray()))
    }

    override fun getSerialForHash(hash: HashRepresentation): Result<Long?> {
        val entry = patches.find { it.hash == hash }
        return Result.success(entry?.serial)
    }
}
