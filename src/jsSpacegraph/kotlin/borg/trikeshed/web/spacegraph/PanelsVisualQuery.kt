package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException

/** A reported CID is a navigation hint, not verified evidence or a review decision. */
private fun appendReportedCurationLinks(target: dynamic, context: dynamic) {
    if (context == null || !isArray(context.candidates)) return
    for (raw in arr(context.candidates)) {
        if (raw == null) continue
        val candidate: dynamic = raw
        if (jsTypeOf(candidate.fact) != "string") continue
        val values = candidate.fields?.curationCid
        if (!isArray(values)) continue
        for (value in arr(values)) {
            if (jsTypeOf(value) != "string") continue
            val cid = str(value)
            if (!Regex("^sha256:[0-9a-f]{64}$").matches(cid)) continue
            target.appendChild(document.createTextNode("\nReported curation reference for " + str(candidate.fact) + " (unverified): "))
            val link = document.createElement("a").asDynamic()
            link.href = "/documents#curation/" + encodeURIComponent(cid)
            link.target = "_blank"; link.rel = "noopener noreferrer"; link.textContent = cid
            target.appendChild(link)
        }
    }
}

/** Explicit read-only query of a snapshot of the canvas, not a program run. */
fun Panels.installVisualQuery() {
    val button = document.createElement("button").asDynamic()
    button.type = "button"; button.textContent = "SPARQL query"
    button.title = "Query the selected dataset with the SPARQL syntax drawn on this canvas"
    byId("runBtn").parentNode.insertBefore(button, byId("stopBtn"))

    val dialog = document.createElement("dialog").asDynamic()
    dialog.setAttribute("aria-label", "Visual SPARQL query")
    dialog.style.cssText = "max-width:min(820px,90vw);max-height:85vh;background:var(--panel);color:var(--ink);border:1px solid var(--line);padding:18px"
    val title = document.createElement("h2"); title.textContent = "Visual SPARQL query"; dialog.appendChild(title)
    val description = document.createElement("p")
    description.textContent = "Run the SPARQL syntax on this canvas as a read-only query. Choose the data to search."
    dialog.appendChild(description)
    val limits = document.createElement("p")
    limits.textContent = "SELECT graph patterns only. A pattern without a graph searches the union of graphs. Large queries may be refused."
    dialog.appendChild(limits)
    val starter = document.createElement("a").asDynamic()
    starter.href = "/panels?load=preset-sparql-quad"; starter.target = "_blank"; starter.rel = "noopener"
    starter.textContent = "Open an editable SPARQL example in a new tab"
    dialog.appendChild(starter)
    val label = document.createElement("label").asDynamic(); label.textContent = "Dataset "
    val select = document.createElement("select").asDynamic(); select.setAttribute("aria-label", "Dataset")
    fun option(value: String, text: String) {
        val o = document.createElement("option").asDynamic(); o.value = value; o.textContent = text; select.appendChild(o)
    }
    option("", "Choose a dataset")
    option("quad-store", "Quad store")
    option("rete-projection", "Live Rete field projection (raw)")
    option("quad-store+rete", "Quad store and live Rete facts")
    label.appendChild(select); dialog.appendChild(label)
    val candidateLabel = document.createElement("label").asDynamic()
    candidateLabel.textContent = " Optional projected proposal-fact variable "
    val candidateVariable = document.createElement("input").asDynamic()
    candidateVariable.type = "text"; candidateVariable.placeholder = "?fact"
    candidateVariable.setAttribute("aria-label", "Projected proposal-fact variable")
    candidateLabel.appendChild(candidateVariable); dialog.appendChild(candidateLabel)
    val candidateHelp = document.createElement("p")
    candidateHelp.textContent = "With live Rete selected, show reported fields for projected proposal facts. These are not verified quotations, review decisions, or witnesses for other bindings."
    dialog.appendChild(candidateHelp)
    val execute = document.createElement("button").asDynamic(); execute.type = "button"; execute.textContent = "Query"
    dialog.appendChild(execute)
    val close = document.createElement("button").asDynamic(); close.type = "button"; close.textContent = "Close"
    dialog.appendChild(close)
    val result = document.createElement("pre").asDynamic()
    result.style.cssText = "white-space:pre-wrap;overflow:auto;max-height:52vh"
    result.setAttribute("aria-live", "polite"); dialog.appendChild(result)
    document.body!!.appendChild(dialog)
    var generation = 0
    page.own { generation++; button.remove(); dialog.remove() }
    page.on(button, "click", {
        generation++; execute.disabled = false
        result.textContent = ""; select.value = ""; candidateVariable.value = ""; dialog.showModal()
    })
    page.on(dialog, "close", { if (!truthy(dialog.open)) { generation++; execute.disabled = false } })
    page.on(close, "click", { generation++; execute.disabled = false; dialog.close() })
    page.on(execute, "click", {
        val dataset = str(select.value)
        if (dataset.isBlank()) result.textContent = "Choose a dataset before querying."
        else {
            // Serialize before the asynchronous request; do not change the canvas's execution controls.
            val specimen = JSON.parse<dynamic>(JSON.stringify(toConfix()))
            if (specimen.controls == null) specimen.controls = obj()
            specimen.controls.inspectionOnly = true
            val candidate = str(candidateVariable.value).trim()
            val body = JSON.stringify(objOf {
                it.dataset = dataset; it.program = specimen
                if (candidate.isNotEmpty()) it.candidateVariable = candidate
            })
            val requestGeneration = ++generation
            execute.disabled = true; result.textContent = "Querying the selected dataset…"
            launchJs {
                try {
                    val response = fetchJs("/sparql/visual", jsonInit("POST", body))
                    val payload: dynamic = JSON.parse(PatchRead.text(response))
                    if (!page.closing && generation == requestGeneration) {
                        if (!truthy(response.ok)) result.textContent = "Query failed: " + str(payload.error ?: "unknown error")
                        else {
                            result.textContent = "Dataset: " + str(payload.dataset) + "\n\n" + str(payload.query) + "\n\n" +
                            str(JSON.stringify(payload.results, null, 2)) +
                            "\n\nSource scope (not review authority):\n" + str(JSON.stringify(payload.source, null, 2)) +
                            (if (payload.candidateContext == null) "" else "\n\nCandidate context (not query witnesses):\n" +
                                str(JSON.stringify(payload.candidateContext, null, 2))) +
                            "\n\nResults are for the canvas snapshot taken when Query was pressed."
                            appendReportedCurationLinks(result, payload.candidateContext)
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: dynamic) { if (!page.closing && generation == requestGeneration) result.textContent = "Query failed: " + failureText(e) }
                finally { if (!page.closing && generation == requestGeneration) execute.disabled = false }
            }
        }
    })
}

/** Query a SQL syntax snapshot explicitly; the program runner remains disabled for specimens. */
fun Panels.installVisualSqlQuery() {
    val button = document.createElement("button").asDynamic()
    button.id = "visualSqlBtn"; button.type = "button"; button.textContent = "SQL query"
    button.title = "Query the local quad store with the SQL syntax drawn on this canvas"
    byId("runBtn").parentNode.insertBefore(button, byId("stopBtn"))

    val dialog = document.createElement("dialog").asDynamic()
    dialog.setAttribute("aria-label", "Visual SQL query")
    dialog.style.cssText = "max-width:min(820px,90vw);max-height:85vh;background:var(--panel);color:var(--ink);border:1px solid var(--line);padding:18px"
    val title = document.createElement("h2"); title.textContent = "Visual SQL query"; dialog.appendChild(title)
    val description = document.createElement("p")
    description.textContent = "Read-only query over the local quad store. Live Rete facts and project tables are not included; terms are stored as text. Results do not establish source verification or review status."
    dialog.appendChild(description)
    val limits = document.createElement("p")
    limits.textContent = "Use projections and simple comparisons. Joins, grouping, sorting, limits, functions and other tables are not supported here."
    dialog.appendChild(limits)
    val starter = document.createElement("a").asDynamic()
    starter.href = "/panels?load=preset-sql-quad"; starter.target = "_blank"; starter.rel = "noopener"
    starter.textContent = "Open an editable SQL example in a new tab"
    dialog.appendChild(starter)
    val label = document.createElement("label").asDynamic(); label.textContent = " Dataset "
    val select = document.createElement("select").asDynamic(); select.setAttribute("aria-label", "SQL dataset")
    fun option(value: String, text: String) {
        val o = document.createElement("option").asDynamic(); o.value = value; o.textContent = text; select.appendChild(o)
    }
    option("", "Choose a dataset"); option("quad-store", "Local quad store")
    label.appendChild(select); dialog.appendChild(label)
    val execute = document.createElement("button").asDynamic(); execute.type = "button"; execute.textContent = "Query"
    dialog.appendChild(execute)
    val close = document.createElement("button").asDynamic(); close.type = "button"; close.textContent = "Close"
    dialog.appendChild(close)
    val result = document.createElement("div").asDynamic()
    result.style.cssText = "overflow:auto;max-height:52vh"; result.setAttribute("aria-live", "polite")
    dialog.appendChild(result); document.body!!.appendChild(dialog)

    fun showResult(payload: dynamic) {
        val isArray: dynamic = js("Array.isArray")
        require(payload != null && str(payload.dataset) == "quad-store" &&
            truthy(isArray(payload.columns)) && truthy(isArray(payload.rows))) { "Invalid visual SQL response" }
        val columns = arr(payload.columns)
        val values = arr(payload.rows)
        require(columns.isNotEmpty() && columns.all { jsTypeOf(it) == "string" } && values.all { row ->
            truthy(isArray(row)) && arr(row).size == columns.size && arr(row).all { value ->
                value == null || jsTypeOf(value) == "string" || jsTypeOf(value) == "number"
            }
        }) { "Invalid visual SQL result shape" }
        result.textContent = ""
        val scope = document.createElement("p")
        scope.textContent = "Dataset: local quad store. Results are from the canvas snapshot taken when Query was pressed."
        result.appendChild(scope)
        val table = document.createElement("table").asDynamic()
        val head = document.createElement("thead").asDynamic(); val header = document.createElement("tr").asDynamic()
        for (column in columns) {
            val th = document.createElement("th"); th.textContent = str(column); header.appendChild(th)
        }
        head.appendChild(header); table.appendChild(head)
        val rows = document.createElement("tbody").asDynamic()
        for (row in values) {
            val tr = document.createElement("tr").asDynamic()
            for (value in arr(row)) {
                val td = document.createElement("td").asDynamic()
                if (value == null) { td.textContent = "NULL"; td.style.fontStyle = "italic"; td.setAttribute("aria-label", "SQL null") }
                else td.textContent = str(value)
                tr.appendChild(td)
            }
            rows.appendChild(tr)
        }
        table.appendChild(rows); result.appendChild(table)
        if (values.isEmpty()) {
            val empty = document.createElement("p"); empty.textContent = "No rows matched."; result.appendChild(empty)
        }
    }

    var generation = 0
    page.own { generation++; button.remove(); dialog.remove() }
    page.on(button, "click", {
        generation++; execute.disabled = false; select.value = ""; result.textContent = ""; dialog.showModal()
    })
    page.on(dialog, "close", { if (!truthy(dialog.open)) { generation++; execute.disabled = false } })
    page.on(close, "click", { generation++; execute.disabled = false; dialog.close() })
    page.on(execute, "click", {
        val dataset = str(select.value)
        if (dataset != "quad-store") result.textContent = "Choose the local quad store before querying."
        else {
            val specimen = JSON.parse<dynamic>(JSON.stringify(toConfix()))
            if (specimen.controls == null) specimen.controls = obj()
            specimen.controls.inspectionOnly = true
            val body = JSON.stringify(objOf { it.dataset = dataset; it.program = specimen })
            val requestGeneration = ++generation
            execute.disabled = true; result.textContent = "Querying the local quad store…"
            launchJs {
                try {
                    val response = fetchJs("/sql/visual", jsonInit("POST", body))
                    val payload: dynamic = JSON.parse(PatchRead.text(response))
                    if (!page.closing && generation == requestGeneration) {
                        if (!truthy(response.ok)) result.textContent = "Query failed: " + str(payload.error ?: "unknown error")
                        else showResult(payload)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: dynamic) { if (!page.closing && generation == requestGeneration) result.textContent = "Query failed: " + failureText(e) }
                finally { if (!page.closing && generation == requestGeneration) execute.disabled = false }
            }
        }
    })
}
