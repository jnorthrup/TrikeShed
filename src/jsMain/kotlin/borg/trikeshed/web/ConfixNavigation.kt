package borg.trikeshed.web

import borg.trikeshed.job.ContentId
import org.w3c.dom.url.URLSearchParams

/** Links select existing immutable JSON; they never carry a program to load or execute. */
object ConfixNavigation {
    fun path(text: String): Array<Any> {
        require(text.length <= 8192) { "Confix path exceeds the link budget" }
        val value = JSON.parse<dynamic>(text)
        require(isArray(value) && num(value.length) <= 32) { "Confix path must be an array of at most 32 steps" }
        return arr(value).map { step ->
            when (typeOf(step)) {
                "string" -> step as String
                "number" -> {
                    val n = num(step)
                    require(n.isFinite() && n >= 0 && n <= Int.MAX_VALUE && n % 1 == 0.0) { "Invalid Confix path ordinal" }
                    n.toInt()
                }
                else -> error("Confix path steps must be keys or nonnegative integer ordinals")
            }
        }.toTypedArray()
    }

    fun href(cid: String, path: String = "[]"): String {
        val query = URLSearchParams()
        query.set("content", ContentId(cid).value)
        query.set("path", JSON.stringify(this.path(path)))
        return "/panels?$query"
    }

    fun requested(query: URLSearchParams): Boolean = query.has("content") || query.has("path")

    fun source(query: URLSearchParams): Pair<String, Array<Any>> {
        require(query.getAll("content").size == 1 && query.getAll("path").size <= 1 &&
            !query.has("load") && !query.has("documentTask") && !DebugNavigation.requested(query)) {
            "Choose one retained content ID, not a program, task or debugger load"
        }
        return ContentId(requireNotNull(query.get("content"))).value to path(query.get("path") ?: "[]")
    }
}
