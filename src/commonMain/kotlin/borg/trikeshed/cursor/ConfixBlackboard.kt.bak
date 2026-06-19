package borg.trikeshed.cursor

import borg.trikeshed.parse.confix.*
import borg.trikeshed.lib.*

/**
 * Alias to support JsElement terminology over JsonElement/ConfixCell.
 * Distinct from PRELOAD.md's JsElement = Join<Twin<Int>, Series<Int>> (JSON indexer)
 */
typealias ConfixJsElement = ConfixCell

/**
 * Confix JsElement accessible blackboard.
 * Centralized registry for BlackBoardEntry records, which encapsulate ConfixDoc.
 */
class ConfixBlackboard {
    private val records = mutableMapOf<String, BlackBoardEntry>()

    fun put(id: String, entry: BlackBoardEntry) {
        records[id] = entry
    }

    fun get(id: String): BlackBoardEntry? = records[id]

    fun remove(id: String): BlackBoardEntry? = records.remove(id)

    /**
     * Navigates to a specific JsElement within the specified entry.
     */
    fun navigate(id: String, path: JsPath): ConfixJsElement? {
        val entry = records[id] ?: return null
        return entry.doc.navigate(path)
    }

    /**
     * Resolves an element using vararg path segments.
     */
    fun getAt(id: String, vararg path: Any): ConfixJsElement? {
        val entry = records[id] ?: return null
        return entry.doc.docAt(*path)
    }

    /**
     * Reifies the scalar value at the specified path.
     */
    fun scalar(id: String, vararg path: Any): Any? {
        val entry = records[id] ?: return null
        return entry.doc.scalar(*path)
    }

    /**
     * Returns all registered blackboard IDs.
     */
    fun ids(): Set<String> = records.keys
}
