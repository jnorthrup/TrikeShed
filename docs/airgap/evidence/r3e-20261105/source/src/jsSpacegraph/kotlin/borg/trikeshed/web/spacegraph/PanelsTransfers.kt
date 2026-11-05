package borg.trikeshed.web.spacegraph

import borg.trikeshed.lib.*
import borg.trikeshed.web.*
import kotlinx.browser.document

/** Bounded wire aggregates, independent of the transient animation queue. */
class PanelsTransfers(parent: dynamic) {
    private class Transfer(val row: dynamic, val countCell: dynamic, val sequenceCell: dynamic,
                           val timeCell: dynamic, val first: Double, var count: Int = 0,
                           var last: String = "")
    private val rows = mutableMapOf<Int, Transfer>()
    private var omitted = 0
    private var selections = 0
    private var selected: Int? = null
    private val details = element("details", parent)
    private val summary = element("summary", details, "Inspect observed transfers")
    private val identity = element("p", details)
    private val viewport = element("div", details)
    private val table = element("table", viewport)
    private val body: dynamic
    private val record: dynamic

    init {
        details.style.cssText = "margin-top:8px;border-top:1px solid #46576c;padding-top:6px"
        summary.style.cursor = "pointer"
        identity.style.cssText = "overflow-wrap:anywhere;white-space:pre-line"
        viewport.style.cssText = "max-height:220px;overflow:auto"
        table.setAttribute("aria-label", "Observed transfer counts")
        table.style.cssText = "width:100%;table-layout:fixed;border-collapse:collapse"
        val head = element("tr", element("thead", table))
        for (name in s_["Wire / last selection", "Count", "Sequence first–last", "Last (ms)"].view) {
            val cell = element("th", head, name); cell.setAttribute("scope", "col")
            cell.style.cssText = "position:sticky;top:0;background:var(--panel,#101620);text-align:left;vertical-align:top;overflow-wrap:anywhere;padding:4px;border-bottom:1px solid #46576c"
        }
        head.firstChild.style.width = "44%"
        body = element("tbody", table)
        element("p", details, "Received selections only; up to 256 mapped wires. Sequence intervals may contain gaps. Inspection pins the last selection; receipts retain their own bounded histories.")
        record = element("pre", details)
        record.setAttribute("aria-label", "Selected transfer observation")
        record.style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere;max-height:160px;overflow:auto;border-top:1px solid #46576c;padding-top:8px"
        record.textContent = "Choose a wire to inspect its last recorded selection."
    }

    private fun element(tag: String, parent: dynamic, text: String = ""): dynamic {
        val el = document.createElement(tag).asDynamic(); el.textContent = text; parent.appendChild(el); return el
    }

    fun observe(index: Int, event: dynamic) {
        selections++
        val transfer = rows[index] ?: run {
            if (rows.size >= 256) { omitted++; return }
            val row = element("tr", body); row.setAttribute("data-transfer-row", str(index))
            val button = element("button", element("td", row))
            button.setAttribute("data-transfer-index", str(index))
            val wire = event.wire
            button.textContent = str(wire.fromNode) + "." + str(wire.fromPort) + " → " + str(wire.toNode) + "." + str(wire.toPort)
            button.setAttribute("aria-label", "Inspect last selection: " + str(button.textContent))
            button.style.cssText = "width:100%;text-align:left;white-space:normal;overflow-wrap:anywhere"
            val count = element("td", row); val sequence = element("td", row); val time = element("td", row)
            for (cell in s_[count, sequence, time].view) cell.style.cssText = "vertical-align:top;padding:4px;overflow-wrap:anywhere"
            Transfer(row, count, sequence, time, num(event.sequence)).also { rows[index] = it }
        }
        transfer.count++
        transfer.countCell.textContent = str(transfer.count)
        transfer.sequenceCell.textContent = str(transfer.first) + "–" + str(event.sequence)
        val elapsed = num(event.elapsedNanos)
        transfer.timeCell.textContent = if (elapsed.isFinite()) fixed(elapsed / 1_000_000, 3) else "unavailable"
        transfer.last = JSON.stringify(event, null, 2)
        // A chosen observation is pinned; new arrivals cannot replace what the operator is reading.
    }

    fun report(run: String, cid: String) {
        summary.textContent = "Inspect observed transfers · " + rows.size + " wires · " + selections + " selections"
        identity.textContent = "Run " + run + "\nProgram " + cid + "\n" + omitted + " selections omitted from this table. Times are measured from walk start, including debugger pauses."
    }

    fun inspect(target: dynamic) {
        val button = target?.closest("[data-transfer-index]") ?: return
        if (!truthy(table.contains(button))) return
        val index = str(button.getAttribute("data-transfer-index")).toIntOrNull() ?: return
        val transfer = rows[index] ?: return
        selected?.let { rows[it]?.row?.removeAttribute("aria-selected") }
        selected = index; transfer.row.setAttribute("aria-selected", "true")
        record.textContent = "Pinned observation · wire sequence interval at selection " + str(transfer.sequenceCell.textContent) +
            "\nThis is recorded input consumption, not current execution state.\n" + transfer.last
    }
}
