package borg.trikeshed.relaxfactory

import borg.trikeshed.couch.ViewRow

/** A design doc's `language` value: `javascript`, `kotlin`. */
typealias ViewLanguage = String

/**
 * The query server for one design-doc `language`: map/reduce function source runs here.
 * [map] sees every document as its full JSON body (`_id` included) and returns the emitted rows;
 * [reduce] folds `(keys, values, rereduce)` the way CouchDB's reduce functions do, keys being
 * `[key, docId]` pairs.
 */
interface ViewLanguageHost {
    fun map(ddoc: String, view: String, source: String, docs: List<Map<String, Any?>>): List<ViewRow>
    fun reduce(ddoc: String, view: String, source: String, keys: List<Any?>, values: List<Any?>, rereduce: Boolean): Any?
}

/** The process's query servers by [ViewLanguage]; targets install theirs at boot. */
object ViewLanguages {
    val hosts: MutableMap<ViewLanguage, ViewLanguageHost> = linkedMapOf()
}
