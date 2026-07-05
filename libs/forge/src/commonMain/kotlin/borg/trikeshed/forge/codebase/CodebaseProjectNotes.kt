package borg.trikeshed.forge.codebase

import borg.trikeshed.forge.ForgeFile
import borg.trikeshed.forge.notion.CursorDrivenNotion
import borg.trikeshed.forge.notion.CursorNotionState
import borg.trikeshed.forge.notion.NotionBlockKind
import borg.trikeshed.forge.notion.NotionKanbanBridge
import borg.trikeshed.userspace.reactor.KanbanFSM
import borg.trikeshed.userspace.reactor.KanbanState

/**
 * CodebaseProjectNotes turns a forge-observed software project into a real note artifact.
 *
 * This is the value seam for Forge's "document-first" metaphor: given a generic
 * [CodebaseInduction], emit project notes that a software team can actually read,
 * persist, export to markdown, and project into the existing taxonomy/kanban spine.
 *
 * No demo subject is baked in. The caller supplies the project induction.
 */
object CodebaseProjectNotes {

    fun render(
        induction: CodebaseInduction,
        actorId: String = "codebase-project-notes",
    ): CursorNotionState {
        val project = induction.forgeProjectState.project
        var state = CursorDrivenNotion.empty(
            title = "${project.name} project notes",
            actorId = actorId,
        )

        state = CursorDrivenNotion.appendBlock(
            state = state,
            parentId = state.rootPageId,
            kind = NotionBlockKind.QUOTE,
            text = summaryLine(induction),
            actorId = actorId,
        )

        for (service in project.services) {
            state = CursorDrivenNotion.appendBlock(
                state = state,
                parentId = state.rootPageId,
                kind = NotionBlockKind.HEADING_2,
                text = service.name,
                properties = serviceProperties(service),
                actorId = actorId,
            )
            state = CursorDrivenNotion.appendBlock(
                state = state,
                parentId = state.rootPageId,
                kind = NotionBlockKind.BULLET,
                text = "manifest: ${service.manifestPath}",
                actorId = actorId,
            )
            state = CursorDrivenNotion.appendBlock(
                state = state,
                parentId = state.rootPageId,
                kind = NotionBlockKind.BULLET,
                text = "build: ${service.buildCommand}",
                actorId = actorId,
            )
            state = CursorDrivenNotion.appendBlock(
                state = state,
                parentId = state.rootPageId,
                kind = NotionBlockKind.BULLET,
                text = serviceSurfaceLine(service),
                actorId = actorId,
            )
        }

        return state
    }

    fun asForgeFile(
        induction: CodebaseInduction,
        actorId: String = "codebase-project-notes",
        path: String? = null,
    ): ForgeFile = CursorDrivenNotion.asForgeMarkdownFile(
        state = render(induction, actorId),
        path = path,
    )

    fun toKanbanState(
        induction: CodebaseInduction,
        actorId: String = "codebase-project-notes",
    ): KanbanState = NotionKanbanBridge.projectTaxonomyEvents(render(induction, actorId))
        .fold(KanbanState()) { acc, event -> KanbanFSM.reduce(event, acc) }

    private fun summaryLine(induction: CodebaseInduction): String {
        val project = induction.forgeProjectState.project
        val services = project.services.size
        val head = project.gitHead.ifBlank { "unknown-head" }
        val classpathRoots = induction.forgeProjectState.classpathRoots.size
        return "Repo ${project.repoPath} @ $head · $services services · $classpathRoots observed roots"
    }

    private fun serviceSurfaceLine(service: CodebaseService): String {
        val entrypointSummary = when {
            service.entryPoints.isEmpty() -> "no declared entrypoints"
            service.entryPoints.size == 1 -> "entrypoint: ${service.entryPoints.first()}"
            else -> "${service.entryPoints.size} entrypoints: ${service.entryPoints.take(2).joinToString()}"
        }
        return "$entrypointSummary · ${service.classpath.size} classpath roots"
    }

    private fun serviceProperties(service: CodebaseService): Map<String, String> = mapOf(
        "service.language" to service.language.name,
        "service.role" to service.role.name,
        "service.manifest" to service.manifestPath,
        "service.build" to service.buildCommand,
    )
}
