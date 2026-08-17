package borg.trikeshed.jules.ui

import borg.trikeshed.forge.blackboard.ForgeBlackboardSection3D
import borg.trikeshed.forge.blackboard.ForgeBlackboardView
import borg.trikeshed.jules.JulesRestClient

object JulesBlackboardAdapter {
    fun projectSessionsToBlackboard(sessions: List<JulesRestClient.SessionInfo>): ForgeBlackboardView {
        val baseView = ForgeBlackboardView.DEFAULT
        val baseLayout = baseView.layout3D
        val baseSections = baseView.sections

        val sessionSections = sessions.mapIndexed { index, session ->
            val x = -640.0 + (index % 3) * 640.0
            val y = 760.0 + (index / 3) * 380.0
            ForgeBlackboardSection3D(
                sectionId = "jules-session-${session.id}",
                centerX = x,
                centerY = y,
                width = 600.0,
                height = 340.0,
                elevation = 12.0
            )
        }

        val sessionSectionIds = sessionSections.map { it.sectionId }

        return baseView.copy(
            sections = baseSections + sessionSectionIds,
            layout3D = baseLayout + sessionSections
        )
    }

    fun projectActivitiesToBlackboard(sessionId: String, activities: List<JulesRestClient.ActivityInfo>): ForgeBlackboardView {
        val baseView = ForgeBlackboardView.DEFAULT
        val baseLayout = baseView.layout3D
        val baseSections = baseView.sections

        val activitySections = activities.mapIndexed { index, activity ->
            val x = 0.0 + (index % 4) * 320.0
            val y = 1100.0 + (index / 4) * 200.0
            ForgeBlackboardSection3D(
                sectionId = "jules-activity-${sessionId}-${activity.id}",
                centerX = x,
                centerY = y,
                width = 280.0,
                height = 160.0,
                elevation = 16.0
            )
        }

        val activitySectionIds = activitySections.map { it.sectionId }

        return baseView.copy(
            sections = baseSections + activitySectionIds,
            layout3D = baseLayout + activitySections
        )
    }
}
