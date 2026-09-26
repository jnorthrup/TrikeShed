package borg.trikeshed.web.pages

/** One entry of the application navigation: id, label, canonical href, and the paths that also count as it. */
class AppDestination(val id: String, val label: String, val href: String, val aliases: List<String>)

/** The application navigation table and its path decisions (application-nav.js). */
object AppDestinations {
    const val STORAGE_KEY = "trikeshed.application.views.v1"

    val all: List<AppDestination> = listOf(
        AppDestination("board", "Board", "/blackboard", listOf("/harness", "/harness.html")),
        AppDestination("graal", "Graal", "/graal", emptyList()),
        AppDestination("panels", "Panels", "/panels", listOf("/panels.html")),
        AppDestination("documents", "Documents", "/documents", listOf("/documents.html")),
        AppDestination("headhunter", "Job agent", "/headhunter", listOf("/headhunter.html")),
        AppDestination("kanban", "Kanban", "/kanban", listOf("/kanban.html")),
        AppDestination("hermes", "Hermes", "/hermes", emptyList()),
        AppDestination("keymux", "KeyMux", "/keymux", emptyList()),
        AppDestination("modelmux", "ModelMux", "/modelmux", listOf("/mux/sessions", "/mux/stats", "/mux.html")),
        AppDestination("futon", "Futon", "/futon", emptyList()),
    )

    private fun trimSlash(path: String): String = if (path.endsWith("/")) path.dropLast(1) else path

    /** The destination the page at [pathname] belongs to, or null when the page is not in the table. */
    fun current(pathname: String): AppDestination? {
        val path = trimSlash(pathname).ifEmpty { "/" }
        return all.firstOrNull { it.href == path || path in it.aliases }
    }

    /** True when a remembered view at [savedPathname] may stand in for [destination]'s href. */
    fun accepts(destination: AppDestination, savedPathname: String): Boolean {
        val path = trimSlash(savedPathname)
        return destination.href == path || path in destination.aliases
    }
}
