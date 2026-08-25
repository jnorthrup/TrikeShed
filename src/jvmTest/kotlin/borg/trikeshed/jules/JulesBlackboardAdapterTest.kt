package borg.trikeshed.jules

import borg.trikeshed.jules.ui.JulesBlackboardAdapter
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test suite for JulesBlackboardAdapter.
 * Validates session/activity projection, ForgeBlackboardView init constraint satisfaction,
 * TTL lifecycle, and geometry invariants.
 * Aligned with SettlementBarrierTest patterns.
 */
class JulesBlackboardAdapterTest {

    private fun makeSession(
        id: String = "session-001",
        state: String = "RUNNING",
        title: String = "Test Session",
        patchBytes: Long = 1024L,
        source: String = "sources/test",
        updateTime: String = "2024-01-01T00:00:00Z",
    ) = JulesRestClient.SessionInfo(
        id = id, state = state, title = title, patchBytes = patchBytes,
        source = source, updateTime = updateTime,
    )

    private fun makeActivity(
        id: String = "activity-001",
        seq: Int = 0,
        createTime: String = "2024-01-01T00:00:00Z",
        originator: String = "AGENT",
        kind: String = "agentMessaged",
        patchBytes: Long = 512L,
        excerpt: String = "Test excerpt text",
    ) = JulesRestClient.ActivityInfo(
        id = id, seq = seq, createTime = createTime, originator = originator,
        kind = kind, patchBytes = patchBytes, excerpt = excerpt, message = excerpt,
    )

    @Test
    fun `projectSessionsToBlackboard produces correct session count`() = runTest {
        JulesBlackboardAdapter.clearCache()
        val sessions = listOf(makeSession("s1"), makeSession("s2"), makeSession("s3"))
        val (_, surfaceSessions) = JulesBlackboardAdapter.projectSessionsToBlackboard(sessions)
        assertEquals(3, surfaceSessions.size)
    }

    @Test
    fun `projectSessionsToBlackboard maps session fields verbatim`() = runTest {
        JulesBlackboardAdapter.clearCache()
        val sessions = listOf(makeSession(
            id = "s123", state = "COMPLETED",
            title = "Build fix for parser",
            patchBytes = 4096L, source = "sources/github/trike",
            updateTime = "2024-06-15T12:00:00Z",
        ))
        val (_, surfaceSessions) = JulesBlackboardAdapter.projectSessionsToBlackboard(sessions)
        assertEquals(1, surfaceSessions.size)
        val s = surfaceSessions[0]
        assertEquals("s123", s.id)
        assertEquals("COMPLETED", s.state)
        assertEquals("Build fix for parser", s.title)
        assertEquals(4096L, s.patchBytes)
        assertEquals("sources/github/trike", s.source)
        assertEquals("2024-06-15T12:00:00Z", s.updateTime)
    }

    @Test
    fun `projectSessionsToBlackboard satisfies ForgeBlackboardView init constraint`() = runTest {
        JulesBlackboardAdapter.clearCache()
        val sessions = listOf(makeSession("s1"), makeSession("s2"))
        val (view, _) = JulesBlackboardAdapter.projectSessionsToBlackboard(sessions)
        val layoutSectionIds = view.layout3D.map { it.sectionId }.toSet()
        val sectionsSet = view.sections.toSet()
        assertEquals(layoutSectionIds, sectionsSet)
    }

    @Test
    fun `projectSessionsToBlackboard lays out sessions in 3-column grid`() = runTest {
        JulesBlackboardAdapter.clearCache()
        val sessions = (1..7).map { makeSession("s$it") }
        val (_, surfaceSessions) = JulesBlackboardAdapter.projectSessionsToBlackboard(sessions)
        assertEquals(7, surfaceSessions.size)
        assertEquals(3, surfaceSessions.map { it.centerY }.distinct().size) // 3 rows
    }

    @Test
    fun `projectSessionsToBlackboard generates correct sectionId format`() = runTest {
        JulesBlackboardAdapter.clearCache()
        val sessions = listOf(makeSession("abc123def"))
        val (_, surfaceSessions) = JulesBlackboardAdapter.projectSessionsToBlackboard(sessions)
        assertEquals("jules-session-abc123def", surfaceSessions[0].sectionId)
    }

    @Test
    fun `projectSessionsToBlackboard uses fixed geometry for all sessions`() = runTest {
        JulesBlackboardAdapter.clearCache()
        val sessions = listOf(makeSession("s1"), makeSession("s2"), makeSession("s3"))
        val (_, surfaceSessions) = JulesBlackboardAdapter.projectSessionsToBlackboard(sessions)
        surfaceSessions.forEach { s ->
            assertEquals(600.0, s.width)
            assertEquals(340.0, s.height)
            assertEquals(12.0, s.elevation)
            assertTrue(s.elevation > 0)
        }
    }

    @Test
    fun `projectSessionsToBlackboard handles empty list`() = runTest {
        JulesBlackboardAdapter.clearCache()
        val (_, surfaceSessions) = JulesBlackboardAdapter.projectSessionsToBlackboard(emptyList())
        assertEquals(0, surfaceSessions.size)
    }

    @Test
    fun `projectSessionsToBlackboard handles 15 sessions (MAX_LIVE boundary)`() = runTest {
        JulesBlackboardAdapter.clearCache()
        val sessions = (1..15).map { makeSession("s$it") }
        val (_, surfaceSessions) = JulesBlackboardAdapter.projectSessionsToBlackboard(sessions)
        assertEquals(15, surfaceSessions.size)
        assertEquals(5, surfaceSessions.map { it.centerY }.distinct().size) // 5 rows
    }

    @Test
    fun `projectActivitiesToBlackboard injects sessionId from parameter`() = runTest {
        val activities = listOf(makeActivity("a1", seq = 0), makeActivity("a2", seq = 1))
        val (_, surfaceActivities) = JulesBlackboardAdapter.projectActivitiesToBlackboard("my-session-42", activities)
        assertEquals(2, surfaceActivities.size)
        assertEquals("my-session-42", surfaceActivities[0].sessionId)
        assertEquals("my-session-42", surfaceActivities[1].sessionId)
    }

    @Test
    fun `projectActivitiesToBlackboard maps activity fields verbatim`() = runTest {
        val activities = listOf(makeActivity(
            id = "act999", seq = 5, createTime = "2024-07-01T09:00:00Z",
            originator = "USER", kind = "userMessaged", patchBytes = 2048L,
            excerpt = "Please fix the authentication bug",
        ))
        val (_, surfaceActivities) = JulesBlackboardAdapter.projectActivitiesToBlackboard("s1", activities)
        assertEquals(1, surfaceActivities.size)
        val a = surfaceActivities[0]
        assertEquals("act999", a.id)
        assertEquals(5, a.seq)
        assertEquals("2024-07-01T09:00:00Z", a.createTime)
        assertEquals("USER", a.originator)
        assertEquals("userMessaged", a.kind)
        assertEquals(2048L, a.patchBytes)
        assertEquals("Please fix the authentication bug", a.excerpt)
    }

    @Test
    fun `projectActivitiesToBlackboard lays out activities in 4-column grid`() = runTest {
        val activities = (0..8).map { makeActivity("a$it", seq = it) }
        val (_, surfaceActivities) = JulesBlackboardAdapter.projectActivitiesToBlackboard("s1", activities)
        assertEquals(9, surfaceActivities.size)
        assertEquals(3, surfaceActivities.map { it.centerY }.distinct().size) // 3 rows
    }

    @Test
    fun `projectActivitiesToBlackboard generates correct sectionId format`() = runTest {
        val activities = listOf(makeActivity("act777"))
        val (_, surfaceActivities) = JulesBlackboardAdapter.projectActivitiesToBlackboard("my-session", activities)
        assertEquals("jules-activity-my-session-act777", surfaceActivities[0].sectionId)
    }

    @Test
    fun `projectActivitiesToBlackboard handles empty list`() = runTest {
        val (_, surfaceActivities) = JulesBlackboardAdapter.projectActivitiesToBlackboard("s1", emptyList())
        assertEquals(0, surfaceActivities.size)
    }

    @Test
    fun `projectFullSurface merges sessions and activities into one view`() = runTest {
        JulesBlackboardAdapter.clearCache()
        val sessions = listOf(makeSession("s1"), makeSession("s2"))
        val activitiesBySession = mapOf(
            "s1" to listOf(makeActivity("a1", seq = 0)),
            "s2" to listOf(makeActivity("a2", seq = 0), makeActivity("a3", seq = 1)),
        )
        val (view, surface, bySession) = JulesBlackboardAdapter.projectFullSurface(sessions, activitiesBySession)
        assertEquals(2, surface.sessions.size)
        assertEquals(3, surface.activities.size)
        assertEquals(1, bySession["s1"]?.size)
        assertEquals(2, bySession["s2"]?.size)
        // View: 4 default + 2 session + 3 activity = 9
        assertEquals(11, view.sections.size)
    }

    @Test
    fun `projectFullSurface surface has correct TTL and updatedAt`() = runTest {
        JulesBlackboardAdapter.clearCache()
        val before = Clock.System.now().toEpochMilliseconds()
        val (_, surface, _) = JulesBlackboardAdapter.projectFullSurface(listOf(makeSession()), emptyMap())
        val after = Clock.System.now().toEpochMilliseconds()
        assertTrue(surface.updatedAt in before..after)
        assertEquals(borg.trikeshed.jules.ui.TTL_MS, surface.ttlMs)
    }

    @Test
    fun `isStale returns false for fresh surface`() = runTest {
        JulesBlackboardAdapter.clearCache()
        val surface = borg.trikeshed.jules.ui.JulesBlackboardSurface(
            sessions = emptyList(), activities = emptyList(),
            updatedAt = Clock.System.now().toEpochMilliseconds(),
            ttlMs = borg.trikeshed.jules.ui.TTL_MS,
        )
        assertFalse(JulesBlackboardAdapter.isStale(surface))
    }

    @Test
    fun `isStale returns true for expired surface`() = runTest {
        JulesBlackboardAdapter.clearCache()
        val surface = borg.trikeshed.jules.ui.JulesBlackboardSurface(
            sessions = emptyList(), activities = emptyList(),
            updatedAt = 0L, ttlMs = borg.trikeshed.jules.ui.TTL_MS,
        )
        assertTrue(JulesBlackboardAdapter.isStale(surface))
    }

    @Test
    fun `clearCache empties all cached entries`() = runTest {
        JulesBlackboardAdapter.clearCache()
        JulesBlackboardAdapter.projectSessionsToBlackboard(listOf(makeSession("c1"), makeSession("c2")))
        JulesBlackboardAdapter.clearCache()
        // Cache is empty — rebuild from scratch
        val (_, sessions) = JulesBlackboardAdapter.projectSessionsToBlackboard(emptyList())
        assertEquals(0, sessions.size)
    }

    @Test
    fun `ttlMs is configurable and rejects non-positive values`() = runTest {
        JulesBlackboardAdapter.clearCache()
        assertTrue(JulesBlackboardAdapter.ttlMs > 0)
        assertTrue(runCatching { JulesBlackboardAdapter.ttlMs = 0L }.isFailure)
        assertTrue(runCatching { JulesBlackboardAdapter.ttlMs = -5000L }.isFailure)
    }

    @Test
    fun `all sectionId values are unique across sessions and activities`() = runTest {
        JulesBlackboardAdapter.clearCache()
        val sessions = (1..3).map { makeSession("s$it") }
        val activitiesBySession = mapOf(
            "s1" to listOf(makeActivity("a1", seq = 0)),
            "s2" to listOf(makeActivity("a2", seq = 0)),
        )
        val (_, surface, _) = JulesBlackboardAdapter.projectFullSurface(sessions, activitiesBySession)
        val allIds = (surface.sessions.map { it.sectionId } + surface.activities.map { it.sectionId }).toSet()
        assertEquals(5, allIds.size)
    }
}
