package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import kotlinx.browser.document

/** Read-only projection of nars.reteFire output. No inference, admission or model dispatch here. */
fun Panels.renderSimulation(n: dynamic, out: dynamic) {
    if (out?.hypothetical != true || !isArray(out.firings) || !isArray(out.rules) || !isArray(out.assertions)) {
        setResult(n, out)
        return
    }
    setResult(n, "")
    val target = n.el.querySelector(".result") ?: return
    fun element(parent: dynamic, tag: String, text: String = ""): dynamic {
        val el = document.createElement(tag).asDynamic()
        el.textContent = text
        parent.appendChild(el)
        return el
    }
    fun disclosure(parent: dynamic, label: String, fill: (dynamic) -> Unit): dynamic {
        val details = element(parent, "details")
        element(details, "summary", label)
        var filled = false
        on(details, "toggle", { _ ->
            if (truthy(details.open) && !filled) { filled = true; fill(details) }
        })
        return details
    }
    val view = element(target, "section")
    view.className = "simulation"
    view.setAttribute("aria-label", "Hypothetical Rete evaluation")
    // Let the result's native overflow scroll; the canvas wheel listener otherwise consumes it as zoom.
    on(view, "wheel", { e -> e.stopPropagation() }, objOf { it.passive = true })
    element(view, "h4", "Hypothetical Rete evaluation")
    element(view, "p", "Assumptions, not observations. Separate dependent support, not probabilities or independent votes. " +
        "No live admission, model call or actuation occurs in this simulation.")
    val assertions = arr(out.assertions)
    val rules = arr(out.rules)
    val firings = arr(out.firings).asList()
    element(view, "p", "${assertions.size} assumptions · ${arr(out.ruleCids).size} evaluated rules · " +
        "${str(out.rejected)} rejected · ${firings.size} firings · discount ${str(out.discount)}")
    disclosure(view, "Assumptions (${assertions.size})") { details ->
        details.appendChild(renderTree(out.assertions, "assertions", 0))
    }
    disclosure(view, "Rules and rejection evidence (${rules.size})") { details ->
        details.appendChild(renderTree(out.rules, "rules", 0))
    }

    val parties = (assertions.map { str(it.obj ?: "") } + firings.map { str(it.matchedObject ?: "") }).distinct()
    val controls = element(view, "div"); controls.className = "simulation-controls"
    val label = element(controls, "label", "Party / object ")
    val select = element(label, "select")
    select.setAttribute("aria-label", "Simulation party / object")
    element(select, "option", "All parties / objects").value = "all"
    for ((index, party) in parties.withIndex()) element(select, "option", party.ifEmpty { "(empty object)" }).value = str(index)
    val previous = element(controls, "button", "Previous firings")
    val next = element(controls, "button", "Next firings")
    val count = element(view, "p"); count.setAttribute("role", "status")
    val list = element(view, "div"); list.className = "simulation-firings"
    var page = 0
    fun render() {
        val index = str(select.value).toIntOrNull()
        val selected = if (index == null) firings else firings.filter { str(it.matchedObject ?: "") == parties[index] }
        val start = page * 16
        val end = minOf(start + 16, selected.size)
        previous.disabled = page == 0; next.disabled = end >= selected.size
        count.textContent = if (selected.isEmpty()) "No firings for these assumptions and evaluated rules. This is not evidence of absence."
            else "Firings ${start + 1}–$end of ${selected.size} · support in milli-evidence"
        list.textContent = ""
        for (firing in selected.subList(start, end)) {
            val card = element(list, "article")
            element(card, "h5", str(firing.matchedObject ?: "").ifEmpty { "(empty object)" } + " · " +
                str(firing.matchedSubject) + " → " + str(firing.consequent))
            val support = element(card, "div"); support.className = "simulation-support"
            element(support, "span", "Positive ${str(firing.supportPositive)}")
            element(support, "span", "Negative ${str(firing.supportNegative)}")
            element(card, "p", str(firing.dependence) + " · floor applied: " + str(firing.floored))
            disclosure(card, "Source quotation and identities") { details ->
                element(details, "p", "Rule: " + str(firing.ruleCid))
                element(details, "p", "Source: " + str(firing.provenanceCid ?: "not retained"))
                element(details, "p", "Firing: " + str(firing.firingCid))
                val sources = rules.filter { it.ruleCid == firing.ruleCid && it.provenanceCid == firing.provenanceCid }
                if (sources.isEmpty()) element(details, "p", "No matching source candidate retained in this output.")
                for ((ordinal, source) in sources.withIndex()) {
                    element(details, "p", "Candidate ${ordinal + 1}/${sources.size} · sentence ${str(source.sentenceIndex ?: "not retained")} · " +
                        "offsets [${str(source.begin ?: "?")}, ${str(source.end ?: "?")}) · copula ${str(source.copula)}")
                    if (typeOf(source.quote) == "string") element(details, "blockquote", str(source.quote))
                    else element(details, "p", "No quotation retained for this candidate.")
                }
            }
        }
    }
    on(select, "change", { _ -> page = 0; render() })
    on(previous, "click", { e -> e.stopPropagation(); if (page > 0) { page--; render() } })
    on(next, "click", { e -> e.stopPropagation(); if (!truthy(next.disabled)) { page++; render() } })
    render()
    disclosure(view, "Raw evaluation (copy / drag values)") { details -> details.appendChild(renderTree(out, "", 0)) }
}
