package borg.trikeshed.jules.ui

import borg.trikeshed.forge.blackboard.ForgeBlackboardSection3D
import borg.trikeshed.forge.blackboard.ForgeBlackboardView
import borg.trikeshed.forge.blackboard.ForgeDomainSurface
import borg.trikeshed.forge.blackboard.ForgeSurfaceGeometry
import borg.trikeshed.agent.ActivityInfo
import borg.trikeshed.agent.SessionInfo
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlinx.datetime.Clock

/**
 * Projected surface content emitted into the Forge blackboard for one Jules session.
 * Carries all render-relevant fields so the browser renderer can draw the session
 * card without a second API round-trip.
 */
data class ForgeSurfaceSession(
    val id: String,
    val state: String,
    val title: String,
    val patchBytes: Long,
    val source: String,
    val updateTime: String,
    override val sectionId: String,
    override val centerX: Double,
    override val centerY: Double,
    override val width: Double,
    override val height: Double,
    override val elevation: Double,
) : ForgeSurfaceGeometry

/**
 * Projected surface content emitted into the Forge blackboard for one Jules activity.
 * Renders inside the parent session section as a timeline entry.
 */
data class ForgeSurfaceActivity(
    val id: String,
    val sessionId: String,
    val seq: Int,
    val createTime: String,
    val originator: String,
    val kind: String,
    val patchBytes: Long,
    val excerpt: String,
    override val sectionId: String,
    override val centerX: Double,
    override val centerY: Double,
    override val width: Double,
    override val height: Double,
    override val elevation: Double,
) : ForgeSurfaceGeometry

/**
 * Aggregated surface projection of all Jules sessions and activities.
 * Serialized into the blackboard seed so the browser renderer hydrates from
 * the server-rendered payload without a live API call.
 *
 * @param sessions  all live sessions at projection time, with layout geometry
 * @param activities  all activities at projection time, nested under session sections
 * @param updatedAt  epoch millis when this projection was minted
 * @param ttlMs  time-to-live for this projection in milliseconds
 */
data class JulesBlackboardSurface(
    val sessions: List<ForgeSurfaceSession>,
    val activities: List<ForgeSurfaceActivity>,
    override val updatedAt: Long = Clock.System.now().toEpochMilliseconds(),
    override val ttlMs: Long = TTL_MS,
) : ForgeDomainSurface {
    /**
     * Every tile this surface puts on the board: sessions first, then their
     * activities. Concatenated once at construction — a `get()` would reallocate
     * on every read and break identity for callers that cache it.
     */
    override val items: List<ForgeSurfaceGeometry> = sessions + activities
}

/**
 * TTL for a JulesBlackboardSurface projection in milliseconds.
 * 5 minutes: long enough for human eyeballs, short enough to catch session state changes.
 * Override with system property: -Djules.blackboard.ttl.ms=<millis>
 */
val TTL_MS: Long = run {
    val override = SystemOperations.default.getProperty("jules.blackboard.ttl.ms")
    if (override != null) override.toLongOrNull() ?: 300_000L else 300_000L
}

/**
 * Session entry held in the adapter cache with TTL metadata.
 */
data class SessionEntry(
    val session: SessionInfo,
    val expiresAt: Long,
) {
    val isExpired: Boolean
        get() = Clock.System.now().toEpochMilliseconds() > expiresAt
}

object JulesBlackboardAdapter {

    // ── Cache (TTL-bearing) ─────────────────────────────────────────────────────

    /** Session entries keyed by session ID, evicted on expiry. */
    private val sessionCache: MutableMap<String, SessionEntry> = mutableMapOf()

    /** Configurable TTL in milliseconds. */
    var ttlMs: Long = TTL_MS
        set(value) {
            require(value > 0) { "TTL must be positive, got $value" }
            field = value
        }

    // ── Session projection ───────────────────────────────────────────────────────

    /**
     * Build the surface projection for a list of Jules sessions.
     *
     * Sessions are laid out in a 3-column grid below the default 4-section layout:
     * row 0: sessions 0-2, row 1: sessions 3-5, …
     * Each session gets a [ForgeBlackboardSection3D] with its session ID as sectionId
     * and a [ForgeSurfaceSession] carrying all render-relevant fields.
     *
     * The returned [ForgeBlackboardView] satisfies the init constraint
     * (layout3D.sectionIds == sections) by extending the DEFAULT sections list.
     *
     * @return a Pair of the updated ForgeBlackboardView and the list of ForgeSurfaceSession
     */
    fun projectSessionsToBlackboard(
        sessions: List<SessionInfo>,
    ): Pair<ForgeBlackboardView, List<ForgeSurfaceSession>> {
        val baseView = ForgeBlackboardView.DEFAULT
        val baseLayout = baseView.layout3D
        val baseSections = baseView.sections

        val now = Clock.System.now().toEpochMilliseconds()
        val surfaceSessions = sessions.mapIndexed { index, session ->
            val col = index % 3
            val row = index / 3
            val x = -640.0 + col * 640.0
            val y = 760.0 + row * 380.0
            ForgeSurfaceSession(
                id = session.id,
                state = session.state,
                title = session.title,
                patchBytes = session.patchBytes,
                source = session.source,
                updateTime = session.updateTime,
                sectionId = "jules-session-${session.id}",
                centerX = x,
                centerY = y,
                width = 600.0,
                height = 340.0,
                elevation = 12.0,
            )
        }

        val sessionSections = surfaceSessions.map { s ->
            ForgeBlackboardSection3D(
                sectionId = s.sectionId,
                centerX = s.centerX,
                centerY = s.centerY,
                width = s.width,
                height = s.height,
                elevation = s.elevation,
            )
        }

        val sessionSectionIds = surfaceSessions.map { it.sectionId }

        val updatedView = baseView.copy(
            sections = baseSections + sessionSectionIds,
            layout3D = baseLayout + sessionSections,
        )

        // Update TTL cache.
        sessions.forEach { session ->
            sessionCache[session.id] = SessionEntry(
                session = session,
                expiresAt = now + ttlMs,
            )
        }

        return updatedView to surfaceSessions
    }

    // ── Activity projection ─────────────────────────────────────────────────────

    /**
     * Build the surface projection for a list of Jules activities under one session.
     *
     * Activities are laid out in a 4-column grid below the session sections,
     * starting at y=1100 with rows of 4 tiles each.
     * Each activity gets a [ForgeBlackboardSection3D] and a [ForgeSurfaceActivity]
     * carrying all render-relevant fields.
     *
     * Note: activities extend the blackboard beyond the session sections.
     * The caller is responsible for merging session and activity sections into
     * a single coherent [ForgeBlackboardView] if both are needed simultaneously.
     *
     * @return a Pair of the updated ForgeBlackboardView and the list of ForgeSurfaceActivity
     */
    fun projectActivitiesToBlackboard(
        sessionId: String,
        activities: List<ActivityInfo>,
    ): Pair<ForgeBlackboardView, List<ForgeSurfaceActivity>> {
        val baseView = ForgeBlackboardView.DEFAULT
        val baseLayout = baseView.layout3D
        val baseSections = baseView.sections

        val surfaceActivities = activities.mapIndexed { index, activity ->
            val col = index % 4
            val row = index / 4
            val x = 0.0 + col * 320.0
            val y = 1100.0 + row * 200.0
            ForgeSurfaceActivity(
                id = activity.id,
                sessionId = sessionId,
                seq = activity.seq,
                createTime = activity.createTime,
                originator = activity.originator,
                kind = activity.kind,
                patchBytes = activity.patchBytes,
                excerpt = activity.excerpt,
                sectionId = "jules-activity-$sessionId-${activity.id}",
                centerX = x,
                centerY = y,
                width = 280.0,
                height = 160.0,
                elevation = 16.0,
            )
        }

        val activitySections = surfaceActivities.map { a ->
            ForgeBlackboardSection3D(
                sectionId = a.sectionId,
                centerX = a.centerX,
                centerY = a.centerY,
                width = a.width,
                height = a.height,
                elevation = a.elevation,
            )
        }

        val activitySectionIds = surfaceActivities.map { it.sectionId }

        val updatedView = baseView.copy(
            sections = baseSections + activitySectionIds,
            layout3D = baseLayout + activitySections,
        )

        return updatedView to surfaceActivities
    }

    // ── Full surface projection ─────────────────────────────────────────────────

    /**
     * Full projection: sessions + activities merged into one [ForgeBlackboardView]
     * and wrapped in a [JulesBlackboardSurface] carrying TTL metadata.
     *
     * Use this when building the seed for the blackboard + dashboard in one pass.
     *
     * @param sessions  all live sessions at projection time
     * @param activitiesBySession  activities grouped by sessionId, as returned by
     *   [JulesRestClient.activityTimeline] or assembled from per-session API calls
     */
    fun projectFullSurface(
        sessions: List<SessionInfo>,
        activitiesBySession: Map<String, List<ActivityInfo>>,
    ): Triple<ForgeBlackboardView, JulesBlackboardSurface, Map<String, List<ForgeSurfaceActivity>>> {
        val (view, surfaceSessions) = projectSessionsToBlackboard(sessions)

        val surfaceActivitiesBySession = activitiesBySession.mapValues { (sid, acts) ->
            acts.mapIndexed { index, act ->
                val col = index % 4
                val row = index / 4
                ForgeSurfaceActivity(
                    id = act.id,
                    sessionId = sid,
                    seq = act.seq,
                    createTime = act.createTime,
                    originator = act.originator,
                    kind = act.kind,
                    patchBytes = act.patchBytes,
                    excerpt = act.excerpt,
                    sectionId = "jules-activity-$sid-${act.id}",
                    centerX = 0.0 + col * 320.0,
                    centerY = 1100.0 + row * 200.0,
                    width = 280.0,
                    height = 160.0,
                    elevation = 16.0,
                )
            }
        }

        val allSurfaceActivities = surfaceActivitiesBySession.values.flatten()

        // Merge activity section3D into the view so both session and activity
        // sections appear on the board.
        val activitySections = allSurfaceActivities.map { a ->
            ForgeBlackboardSection3D(
                sectionId = a.sectionId,
                centerX = a.centerX,
                centerY = a.centerY,
                width = a.width,
                height = a.height,
                elevation = a.elevation,
            )
        }
        val activitySectionIds = allSurfaceActivities.map { it.sectionId }

        val mergedView = view.copy(
            sections = view.sections + activitySectionIds,
            layout3D = view.layout3D + activitySections,
        )

        val surface = JulesBlackboardSurface(
            sessions = surfaceSessions,
            activities = allSurfaceActivities,
            updatedAt = Clock.System.now().toEpochMilliseconds(),
            ttlMs = ttlMs,
        )

        return Triple(mergedView, surface, surfaceActivitiesBySession)
    }

    // ── TTL management ─────────────────────────────────────────────────────────

    /**
     * Evict all expired entries from the session cache.
     * Safe to call on every projection; returns empty list when nothing has expired.
     * @return list of session IDs that were evicted
     */
    fun evictExpired(): List<String> {
        val now = Clock.System.now().toEpochMilliseconds()
        val evicted = sessionCache.entries.filter { (_, entry) -> now > entry.expiresAt }.map { it.key }
        sessionCache.entries.removeAll { (_, entry) -> now > entry.expiresAt }
        return evicted
    }

    /**
     * Check whether the blackboard projection is stale (TTL expired).
     * Returns true when the projection is older than [ttlMs].
     */
    fun isStale(surface: JulesBlackboardSurface): Boolean {
        evictExpired()
        val age = Clock.System.now().toEpochMilliseconds() - surface.updatedAt
        return age > surface.ttlMs
    }

    /**
     * Expire all cached entries immediately (e.g. on logout or session switch).
     */
    fun clearCache() {
        sessionCache.clear()
    }
}
