package borg.literbike.ccek.store.session

/**
 * Session HTTP routes
 *
 * Provides REST API endpoints for session management.
 *
 * UNSAFE: This uses Ktor routing. In production, adapt to your HTTP framework.
 * The Rust version uses Axum; Kotlin equivalent uses Ktor.
 */

import borg.literbike.ccek.store.session.SessionChannel

/** Request/response types */

data class CreateSessionBody(
    val sessionId: String? = null,
)

data class RecordTurnBody(
    val role: String,
    val content: String,
)

data class PatchFeedQuery(
    val since: String? = null,
)

/** Response types */
data class SessionResponse(
    val sessionId: String,
    val ok: Boolean,
    val error: String? = null,
    val hash: String? = null,
)

data class PatchesResponse(
    val patches: List<String>,
    val sessionId: String,
    val ok: Boolean = true,
    val error: String? = null,
)

/**
 * Session manager holding all active sessions.
 */
class SessionManager {
    private val sessions = mutableMapOf<String, SessionChannel>()

    companion object {
        fun create(): SessionManager = SessionManager()
    }

    fun getSessions(): Map<String, SessionChannel> = sessions.toMap()

    /**
     * Create a new session.
     */
    fun createSession(body: CreateSessionBody?): Result<SessionResponse> {
        val sessionId = body?.sessionId ?: generateUUID()

        return SessionChannel.openChannel(sessionId).map { channel ->
            sessions[sessionId] = channel
            SessionResponse(sessionId = sessionId, ok = true)
        }.getOrElse { error ->
            SessionResponse(sessionId = sessionId, ok = false, error = error.message)
        }
    }

    /**
     * Record a turn in a session.
     */
    fun recordTurn(sessionId: String, body: RecordTurnBody): Result<SessionResponse> {
        val channel = sessions[sessionId]
            ?: return Result.success(SessionResponse(
                sessionId = sessionId,
                ok = false,
                error = "session not found",
            ))

        return recordTurn(channel, body.role, body.content).map { hash ->
            SessionResponse(sessionId = sessionId, ok = true, hash = hash)
        }.getOrElse { error ->
            SessionResponse(sessionId = sessionId, ok = false, error = error.message)
        }
    }

    /**
     * Get patches for a session.
     */
    fun getPatches(sessionId: String, since: String?): Result<PatchesResponse> {
        val channel = sessions[sessionId]
            ?: return Result.success(PatchesResponse(
                patches = emptyList(),
                sessionId = sessionId,
                ok = false,
                error = "session not found",
            ))

        return patchFeed(channel, since).map { patches ->
            PatchesResponse(patches = patches, sessionId = sessionId)
        }.getOrElse { error ->
            PatchesResponse(
                patches = emptyList(),
                sessionId = sessionId,
                ok = false,
                error = error.message,
            )
        }
    }

    /**
     * Revert a turn in a session.
     */
    fun revertTurn(sessionId: String, hash: String): Result<SessionResponse> {
        val channel = sessions[sessionId]
            ?: return Result.success(SessionResponse(
                sessionId = sessionId,
                ok = false,
                error = "session not found",
            ))

        return revertTurn(channel, hash).map {
            SessionResponse(sessionId = sessionId, ok = true)
        }.getOrElse { error ->
            SessionResponse(sessionId = sessionId, ok = false, error = error.message)
        }
    }
}

/**
 * Define session routes.
 *
 * UNSAFE: In Ktor, you would use:
 * ```kotlin
 * routing {
 *     post("/session") { ... }
 *     post("/session/{id}/turns") { ... }
 *     get("/session/{id}/patches") { ... }
 *     delete("/session/{id}/turns/{hash}") { ... }
 * }
 * ```
 *
 * This function returns the route definitions as data for the caller to wire up.
 */
data class RouteDefinition(
    val method: HttpMethod,
    val path: String,
)

enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH
}

/**
 * Get the session route definitions.
 * Wire these up to your HTTP framework's router.
 */
fun sessionRoutes(): List<RouteDefinition> {
    return listOf(
        RouteDefinition(method = HttpMethod.POST, path = "/session"),
        RouteDefinition(method = HttpMethod.POST, path = "/session/{id}/turns"),
        RouteDefinition(method = HttpMethod.GET, path = "/session/{id}/patches"),
        RouteDefinition(method = HttpMethod.DELETE, path = "/session/{id}/turns/{hash}"),
    )
}

/** Generate a UUID v4 string */
private fun generateUUID(): String {
    // UNSAFE: In production, use java.util.UUID.randomUUID().toString()
    return java.util.UUID.randomUUID().toString()
}
