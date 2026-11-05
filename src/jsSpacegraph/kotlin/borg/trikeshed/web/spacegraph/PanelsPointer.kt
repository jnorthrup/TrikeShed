package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import kotlinx.browser.document

/**
 * The presentation pointer: an SVG arrow drawn at the last pointer or drag position, for
 * recordings of dispatched input, which carry no system cursor. `/panels?pointer` installs it.
 */
class PanelsPointer(private val p: Panels) {
    fun install() {
        val el = document.createElementNS("http://www.w3.org/2000/svg", "svg").asDynamic()
        el.id = "pointer"; el.setAttribute("viewBox", "0 0 24 24"); el.setAttribute("aria-hidden", "true")
        el.innerHTML = "<path d='M3 2l7.5 19 2.6-7.9 7.9-2.6z'/>"
        document.body!!.appendChild(el); document.body!!.classList.add("pointer")
        p.page.own { el.remove(); document.body?.classList?.remove("pointer") }
        val track: (dynamic) -> Unit = { e -> el.style.transform = "translate(" + str(e.clientX) + "px," + str(e.clientY) + "px)" }
        val capture = objOf { it.capture = true; it.passive = true }
        p.page.on(document, "pointermove", track, capture)
        p.page.on(document, "dragover", track, capture)
        p.page.on(document, "pointerdown", { e -> track(e); el.classList.add("pressed") }, capture)
        p.page.on(document, "pointerup", { el.classList.remove("pressed") }, capture)
    }
}
