package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import kotlinx.browser.document

/** Draft presentation of one authored Confix parameter, never an evaluator or live guest editor. */
class PanelsSource(val panels: Panels) {
    private var dismiss: (() -> Unit)? = null

    fun open(node: dynamic, parameter: String, control: dynamic, description: String) {
        if (panels.page.closing || dismiss != null || truthy(control.disabled)) return
        val graph = panels.graph
        val id = str(node.id)
        val type = str(node.type)
        val params = node.params
        val before = JSON.stringify(params)
        val element = node.el
        val original = str(params[parameter] ?: "")
        val dialog = document.createElement("dialog").asDynamic()
        dialog.setAttribute("aria-label", "Source editor")
        dialog.style.cssText = "width:min(960px,90vw);max-width:90vw;height:min(740px,85vh);max-height:85vh;box-sizing:border-box;padding:20px;border:1px solid var(--cyan,#57d3ff);border-radius:12px;background:var(--panel,#101620);color:var(--ink,#dce6f3);box-shadow:0 24px 80px #0009;font:13px/1.5 var(--mono,monospace)"
        val frame = document.createElement("div").asDynamic()
        frame.style.cssText = "height:100%;display:flex;flex-direction:column;gap:10px;min-height:0"
        dialog.appendChild(frame)
        fun text(tag: String, value: String): dynamic {
            val item = document.createElement(tag).asDynamic()
            item.textContent = value; item.style.margin = "0"; frame.appendChild(item)
            return item
        }
        text("h2", "Source · $id")
        text("div", "$type · $parameter · " + str(params.language ?: params.facet ?: "language supplied by the VM"))
        if (description.isNotBlank()) text("p", description)
        text("p", "Draft only. Apply changes this authored parameter, not a retained or paused run. Wired inputs still take precedence. Run or Debug explicitly to execute.")
        val editor = document.createElement("textarea").asDynamic()
        editor.value = original; editor.spellcheck = false; editor.wrap = "off"
        editor.setAttribute("aria-label", "$id $parameter source draft")
        editor.style.cssText = "flex:1;min-height:140px;resize:none;box-sizing:border-box;width:100%;padding:14px;border:1px solid var(--line,#263348);border-radius:6px;background:var(--bg,#090e16);color:inherit;font:14px/1.6 var(--mono,monospace);tab-size:4"
        frame.appendChild(editor)
        val state = text("p", "Unchanged draft")
        state.setAttribute("role", "status")
        val actions = document.createElement("div").asDynamic()
        actions.style.cssText = "display:flex;gap:10px;justify-content:flex-end"
        frame.appendChild(actions)
        val cancel = document.createElement("button").asDynamic()
        cancel.textContent = "Cancel"; cancel.type = "button"; actions.appendChild(cancel)
        val apply = document.createElement("button").asDynamic()
        apply.textContent = "Apply source"; apply.type = "button"; actions.appendChild(apply)
        val releases = mutableListOf<() -> Unit>()
        fun on(target: dynamic, event: String, handler: (dynamic) -> Unit) {
            panels.page.on(target, event, handler)
            releases.add { panels.page.off(target, event, handler) }
        }
        var forget: () -> Unit = {}
        var closed = false
        fun close() {
            if (closed) return
            closed = true; dismiss = null; forget()
            for (release in releases) release()
            releases.clear()
            if (truthy(dialog.open)) dialog.close()
            dialog.remove()
        }
        dismiss = ::close
        forget = panels.page.own(::close)
        on(dialog, "cancel") { it.preventDefault(); close() }
        on(dialog, "close") { close() }
        on(dialog, "keydown") { it.stopPropagation() }
        on(dialog, "pointerdown") { it.stopPropagation() }
        on(editor, "input") {
            state.textContent = if (str(editor.value) == original) "Unchanged draft" else "Draft changed · not applied"
        }
        on(cancel, "click") { close() }
        on(apply, "click") {
            if (panels.page.closing || panels.graph !== graph || panels.node(id) !== node ||
                str(node.type) != type || node.el !== element || node.params !== params ||
                JSON.stringify(params) != before || truthy(control.disabled)) {
                state.textContent = "Source not applied: node or parameters changed. Copy the draft, cancel, then reopen the current node."
                return@on
            }
            val value = str(editor.value)
            if (value != original) {
                panels.history.boundary()
                params[parameter] = value; control.value = value
                control.rows = maxOf(3, minOf(16, value.split("\n").size))
                panels.save()
                panels.history.boundary()
                panels.status("Source applied to $id · $parameter. No execution; retained runs remain unchanged.")
            }
            close()
        }
        document.body!!.appendChild(dialog)
        try { dialog.showModal(); editor.focus() }
        catch (failure: dynamic) { close(); throw failure }
    }
}
