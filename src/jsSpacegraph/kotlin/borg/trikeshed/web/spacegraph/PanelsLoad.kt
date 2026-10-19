package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import kotlinx.browser.document

/** Availability/shape guard only. Semantic port/kind authority remains LcncTypeCheck. */
fun Panels.loadFailure(data: dynamic): String? {
    if (data == null || !isArray(data.nodes) || !isArray(data.wires))
        return "A panel must contain nodes and wires arrays"
    val ids = HashSet<String>()
    val available = keysOf(contracts).toSet()
    val missing = linkedSetOf<String>()
    fun visit(n: dynamic): String? {
        if (n == null || typeOf(n.id) != "string" || str(n.id).isBlank() || typeOf(n.type) != "string")
            return "Each node must have a nonempty string id and a string type"
        if (!ids.add(str(n.id))) return "Duplicate node id: ${str(n.id)}"
        if (str(n.type) !in available) missing.add(str(n.type))
        if (n.children != null && !isArray(n.children)) return "Children of ${str(n.id)} must be an array"
        for (child in arr(n.children)) visit(child)?.let { return it }
        return null
    }
    for (n in arr(data.nodes)) visit(n)?.let { return it }
    if (missing.isNotEmpty()) return "Missing node contracts: ${missing.joinToString(", ")}"
    for (w in arr(data.wires)) {
        if (w == null || !isArray(w.from) || !isArray(w.to) || num(w.from.length) != 2.0 || num(w.to.length) != 2.0)
            return "Each wire must have from/to [node, port] endpoints"
        if (arr(w.from).any { typeOf(it) != "string" } || arr(w.to).any { typeOf(it) != "string" })
            return "Wire node ids and ports must be strings"
        if (str(w.from[0]) !in ids || str(w.to[0]) !in ids)
            return "Wire references a missing node: ${str(w.from[0])} → ${str(w.to[0])}"
    }
    return null
}

/** Persistent refusal: background status messages cannot erase load evidence. */
fun Panels.refuseLoad(reason: String, retry: (() -> Unit)? = null): Boolean {
    if (page.closing) return false
    val message = "Panel not loaded: $reason. Current canvas and saved scratch are unchanged."
    status(message)
    byId("panelLoadRefusal")?.remove()
    val notice = document.createElement("section").asDynamic()
    notice.id = "panelLoadRefusal"; notice.setAttribute("role", "alert")
    notice.setAttribute("aria-label", "Panel load refused")
    notice.style.cssText = "position:fixed;bottom:44px;left:16px;max-width:min(620px,85vw);padding:12px;background:var(--panel,#101923);color:var(--ink,#e7edf5);border:1px solid var(--warn,#ffb02e);border-radius:8px;z-index:10000;box-shadow:0 4px 20px #0008"
    val detail = document.createElement("p").asDynamic(); detail.textContent = message
    detail.style.cssText = "margin:0 0 8px;white-space:pre-wrap;overflow-wrap:anywhere"
    notice.appendChild(detail)
    val refresh = document.createElement("button").asDynamic(); refresh.textContent = "Retry contracts"
    notice.appendChild(refresh)
    on(refresh, "click", {
        refresh.disabled = true
        if (retry != null) retry()
        else launchJs {
            try {
                if (syncContracts(announce = false)) {
                    buildPalette()
                    detail.textContent = "$message Contracts refreshed; retry the load (or reload the page to restore scratch)."
                } else detail.textContent = "$message Contracts are still unavailable; retry when the daemon is ready."
            } finally { refresh.disabled = false }
        }
    })
    val dismiss = document.createElement("button").asDynamic(); dismiss.textContent = "Dismiss"
    dismiss.style.marginLeft = "8px"; notice.appendChild(dismiss)
    on(dismiss, "click", { notice.remove() })
    document.body!!.appendChild(notice)
    return false
}
