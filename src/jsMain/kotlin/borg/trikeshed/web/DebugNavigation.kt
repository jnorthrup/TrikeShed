package borg.trikeshed.web

import org.w3c.dom.url.URLSearchParams

/** A controller token may be reused; a recovery link must also pin the admitted run. */
object DebugNavigation {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun requested(query: URLSearchParams): Boolean = query.has("debug") || query.has("run")

    fun source(query: URLSearchParams): Pair<String, String> {
        require(query.getAll("debug").size == 1 && query.getAll("run").size == 1 &&
            listOf("load", "documentTask", "content", "path").none { query.has(it) }) {
            "Choose one debug token and run ID, not a construction or content load"
        }
        val token = query.get("debug").orEmpty()
        val run = query.get("run").orEmpty()
        require(uuid.matches(token) && uuid.matches(run)) { "Invalid debug token or run ID" }
        return token to run
    }

    fun href(token: String, run: String): String {
        val query = URLSearchParams()
        query.set("debug", token); query.set("run", run)
        source(query)
        return "/panels?$query"
    }
}
