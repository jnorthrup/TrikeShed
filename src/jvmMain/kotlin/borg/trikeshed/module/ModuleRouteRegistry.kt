package borg.trikeshed.module

import borg.trikeshed.litebike.JvmKanbanServer
import kotlin.concurrent.Volatile

/** Same shape as the server's ExtraRoute wires (PatchWire/BeliefWire.route). */
typealias ModuleRoute = suspend (
    method: String,
    path: String,
    text: String,
    respond: (suspend (ByteArray) -> Unit)?,
) -> JvmKanbanServer.HttpResponse?

/**
 * ModuleRouteRegistry — the mutable seam that makes modules DYNAMIC: claims are
 * copy-on-write maps consulted per request (reads are a volatile load, zero
 * locks on the hot path), so a module hot-attached after boot answers the very
 * next request and a detached module stops answering atomically.
 *
 * Discipline: EXACT paths only — never "/", never a prefix pattern. Prefix
 * greed is how a module accidentally shadows the shell, static assets, or
 * another wire; a module that wants a family of paths claims each one.
 * Claimed paths SHADOW the server's static ForgeRoutes match (that precedence
 * is the point: KanbanModule takes /api/board away from the fossil parser),
 * which is exactly why "/" is refused.
 */
class ModuleRouteRegistry {
    class Claim(
        val moduleId: String,
        val route: ModuleRoute,
        val streaming: Boolean,
    )

    @Volatile
    private var claims: Map<String, Claim> = emptyMap()

    private val lock = Any()

    /** Hot path: one volatile read + map get. Null = not a module path, fall through. */
    fun match(path: String): Claim? = claims[path.substringBefore('?')]

    fun isStreaming(path: String): Boolean = claims[path.substringBefore('?')]?.streaming == true

    fun claim(moduleId: String, path: String, streaming: Boolean = false, route: ModuleRoute) {
        require(path.startsWith("/api/") && path.length > "/api/".length) { "module routes are exact /api/* paths, got '$path'" }
        require(!path.endsWith("/")) { "no prefix claims: '$path'" }
        synchronized(lock) {
            val existing = claims[path]
            require(existing == null) { "path '$path' already claimed by module '${existing!!.moduleId}'" }
            claims = claims + (path to Claim(moduleId, route, streaming))
        }
    }

    /** Atomic un-claim of everything a module holds — detach takes its routes with it. */
    fun release(moduleId: String): List<String> = synchronized(lock) {
        val mine = claims.filterValues { it.moduleId == moduleId }.keys.toList()
        if (mine.isNotEmpty()) claims = claims - mine.toSet()
        mine
    }

    fun paths(): Map<String, String> = claims.mapValues { it.value.moduleId }
}
