package borg.trikeshed.web

import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.confix.Syntax
import org.w3c.dom.url.URLSearchParams

/** Links select existing immutable content; they never carry a program to load or execute. */
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

    fun href(cid: String, path: String = "[]", syntax: Syntax = Syntax.JSON): String {
        require(syntax == Syntax.JSON || syntax == Syntax.CBOR) { "Unsupported retained syntax" }
        val query = URLSearchParams()
        query.set("content", ContentId(cid).value)
        query.set("path", JSON.stringify(this.path(path)))
        if (syntax != Syntax.JSON) query.set("syntax", syntax.name)
        return "/panels?$query"
    }

    fun requested(query: URLSearchParams): Boolean = query.has("content") || query.has("path") || query.has("syntax")

    fun source(query: URLSearchParams): Triple<String, Array<Any>, Syntax> {
        require(query.getAll("content").size == 1 && query.getAll("path").size <= 1 &&
            query.getAll("syntax").size <= 1 &&
            !query.has("load") && !query.has("documentTask") && !DebugNavigation.requested(query)) {
            "Choose one retained content ID, not a program, task or debugger load"
        }
        val syntax = when (query.get("syntax")) {
            null, "JSON" -> Syntax.JSON
            "CBOR" -> Syntax.CBOR
            else -> error("Retained syntax must be JSON or CBOR")
        }
        return Triple(ContentId(requireNotNull(query.get("content"))).value, path(query.get("path") ?: "[]"), syntax)
    }
}
