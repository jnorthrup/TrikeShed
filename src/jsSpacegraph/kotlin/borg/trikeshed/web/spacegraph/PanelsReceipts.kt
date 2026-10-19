package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException

/** Immutable run evidence; explicit handoffs only load constructions, never execute them. */
fun PanelsFacets.receipts(workspace: dynamic, serial: Int, inspectedRequest: String) {
    val latest = panels.lastProgramRun
    val nodeId = evidenceNode
    text("Recent recorded runs · up to eight immutable receipts. A matching program version does not mean its inputs or dependencies are still current.")
    val section = document.createElement("section").asDynamic()
    section.setAttribute("aria-label", "Durable run inspection"); body.appendChild(section)
    val selected = document.createElement("input").asDynamic()
    selected.setAttribute("aria-label", "Run receipt content ID")
    selected.placeholder = "sha256:… (also accepts receipts from inline runs)"
    selected.style.cssText = "width:100%;box-sizing:border-box;font:inherit"
    section.appendChild(selected)
    val output = document.createElement("div").asDynamic()
    output.setAttribute("aria-live", "polite")
    var request = 0
    fun current(ticket: Int) = serial == revision && drawer != null && ticket == request
    fun url(cid: String) = "/api/lcnc/content?cid=" + encodeURIComponent(cid)
    suspend fun content(cid: String): dynamic {
        require(Regex("sha256:[0-9a-f]{64}").matches(cid)) { "Use a complete sha256 content ID" }
        val response = fetchJs(url(cid), objOf { it.method = "GET" })
        val value: dynamic = JSON.parse(PatchRead.text(response))
        if (num(response.status).toInt() != 200) error(str(value.error ?: "Content unavailable"))
        return value
    }
    fun objectValue(value: dynamic) = value != null && typeOf(value) == "object" && !isArray(value)
    fun details(label: String, value: dynamic, target: dynamic = output) {
        val d = document.createElement("details").asDynamic()
        val s = document.createElement("summary").asDynamic(); s.textContent = label
        d.appendChild(s); pre(executionPreview(value), d); target.appendChild(d)
    }
    fun reference(label: String, cid: String, ticket: Int, target: dynamic = output,
                  render: ((dynamic, dynamic) -> Unit)? = null) {
        val d = document.createElement("details").asDynamic()
        val s = document.createElement("summary").asDynamic(); s.textContent = label + " · " + cid
        d.appendChild(s); target.appendChild(d)
        link("Immutable JSON", url(cid), d)
        link("Confix sheets", url(cid) + "&view=sheet", d)
        button("Inspect $label in Confix", { if (current(ticket)) inspectContent(cid) }, d)
        val source = document.createElement("div").asDynamic(); d.appendChild(source)
        var pending = false
        var loaded = false
        on(d, "toggle", {
            if (truthy(d.open) && !loaded && !pending && current(ticket)) {
                pending = true; source.textContent = "Reading immutable content…"
                launchJs {
                    try {
                        val value = content(cid)
                        if (current(ticket)) {
                            source.textContent = ""
                            if (render == null) pre(executionPreview(value), source) else render(value, source)
                            loaded = true
                        }
                    } catch (e: CancellationException) { throw e } catch (e: dynamic) {
                        if (current(ticket)) source.textContent = "Content inspection failed: " + failureText(e)
                    } finally { pending = false }
                }
            }
        })
    }
    fun inspect(cid: String, expected: dynamic = null) {
        val ticket = ++request
        selected.value = cid; output.textContent = "Reading run receipt…"
        launchJs {
            try {
                val command = content(cid)
                if (!current(ticket)) return@launchJs
                val run = command?.lcncRun
                require(objectValue(run) && typeOf(run.runId) == "string" && typeOf(run.status) == "string" &&
                    typeOf(run.programCid) == "string") { "Content is not an LCNC run receipt" }
                require(command.jobId == "lcnc/run/" + str(run.runId)) { "Receipt run identity mismatch" }
                if (expected != null) require(cid == expected.receiptCid &&
                    run.runId == expected.runId && run.programCid == expected.programCid) {
                    "Receipt identity mismatch: receiptCid, runId or programCid differs from the selected run snapshot"
                }
                selectedReceipt = objOf { it.receiptCid = cid; it.runId = run.runId; it.programCid = run.programCid }
                output.textContent = ""
                text(str(run.program) + " · " + str(run.runId) + " · " + str(run.status), output)
                text("Receipt: " + cid + "\nProgram: " + str(run.programCid) +
                    if (run.programCid == workspace.programCid) "\nSame program version as the inspected canvas."
                    else "\nDifferent program version from the inspected canvas. Inspection does not change the canvas.", output)
                val comparison = if (run.programCid != workspace.programCid) document.createElement("p").asDynamic() else null
                if (comparison != null) {
                    comparison.textContent = "Comparing the frozen program with the inspected canvas…"
                    output.appendChild(comparison)
                }
                if (str(run.status) in arrayOf("validating", "running"))
                    text("This receipt records a non-terminal state, not the run's current liveness. A newer state requires a newer receipt.", output)
                text("Started: " + str(run.startedAtMs ?: "not recorded") + " · finished: " + str(run.finishedAtMs ?: "not recorded") + " (epoch ms)", output)
                link("Immutable receipt JSON", url(cid), output)
                link("Receipt as Confix sheets", url(cid) + "&view=sheet", output)
                button("Inspect receipt in Confix", { if (current(ticket)) inspectContent(cid, arrayOf("lcncRun")) }, output)
                details("Invocation inputs (root inputs, not per-node input snapshots)", run.inputs)
                if (run.error != null || run.violations != null) details("Failure / refusal diagnostics", objOf {
                    it.phase = run.phase; it.error = run.error; it.violations = run.violations
                })
                details("Execution budgets and version policy", objOf { it.budgets = run.budgets; it.versionPolicy = run.versionPolicy })
                if (run.returns != null) details("Returned values", run.returns)
                val outputs = run.outputs
                if (objectValue(outputs)) {
                    val ids = keysOf(outputs).filter { nodeId == null || it == nodeId }
                    val rows = document.createElement("section").asDynamic(); output.appendChild(rows)
                    var offset = 0
                    fun render() {
                        rows.textContent = ""
                        text("Recorded node outputs · ${ids.size} nodes. Missing outputs are not proof that a node did not run; truncation markers remain visible.", rows)
                        if (nodeId != null && ids.isEmpty()) text("No top-level output snapshot for $nodeId. Nested calls may appear in Node invocations below.", rows)
                        for (id in ids.drop(offset).take(32)) {
                            details(id, outputs[id], rows)
                            button("Inspect output $id in Confix", {
                                if (current(ticket)) inspectContent(cid, arrayOf("lcncRun", "outputs", id))
                            }, rows)
                            if (run.status == "completed" && run.ok == true && outputs[id]?.record?.status == "ok" &&
                                typeOf(outputs[id]?.record?.seat) == "string" &&
                                arr(run.consumed).count { it.kind == "document-curation" } == 1)
                                button("Load review task for $id (no execution)", {
                                    val before = JSON.stringify(panels.toConfix())
                                    launchJs {
                                        try {
                                            val frozen = content(str(run.programCid))
                                            require(arr(frozen.nodes).singleOrNull { it.id == id }?.type == "council.seat") {
                                                "Choose a top-level council seat from the frozen program"
                                            }
                                            val recipes = fetchJson("/api/panels/presets")
                                            if (!current(ticket)) return@launchJs
                                            val recipe = arr(recipes.presets).single { it.name == "preset-document-proposal-review" }
                                            val doc = JSON.parse<dynamic>(JSON.stringify(recipe.document))
                                            val task = arr(doc.nodes).single { it.type == "document.reviewTask" }
                                            task.params.runReceiptCid = cid; task.params.nodeId = id
                                            require(before == JSON.stringify(panels.toConfix())) { "Canvas changed; inspect the receipt again before loading" }
                                            if (!panels.load(fromConfix(doc))) return@launchJs
                                            panels.panelName().value = "document-proposal-review"
                                            panels.status("Review task loaded without execution. Inspect the retained seat and source, write a question, then Run to create one local TODO card.")
                                            close()
                                        } catch (e: CancellationException) { throw e } catch (e: dynamic) {
                                            panels.status("Review task not loaded: " + failureText(e))
                                        }
                                    }
                                }, rows)
                        }
                        if (offset > 0) button("Previous node outputs", { offset = maxOf(0, offset - 32); render() }, rows)
                        if (offset + 32 < ids.size) button("Next node outputs", { offset += 32; render() }, rows)
                    }
                    render()
                } else text("No node output snapshot in this receipt. Earlier effects cannot be inferred from that absence.", output)
                if (run.invocationsCid != null) {
                    reference("Node invocations", str(run.invocationsCid), ticket) { evidence, target ->
                        require(evidence.kind == "lcnc-invocations" && evidence.runId == run.runId &&
                            evidence.programCid == run.programCid && isArray(evidence.entries)) { "Invocation evidence identity mismatch" }
                        text("${str(evidence.attempted)} node entries · ${str(evidence.dropped)} omitted. " +
                            "Retained in entry order, with parent invocation and zero-based iteration. " +
                            "Inputs and outputs are boundary snapshots; omission markers are not empty values. " +
                            "This is execution evidence, not breakpoint control or proof of external effects.", target)
                        // Filtering must not renumber the immutable ledger's Confix paths.
                        val entries = arr(evidence.entries).withIndex().filter { nodeId == null || str(it.value.nodeId) == nodeId }
                        text("${entries.size} matching retained invocations" + (nodeId?.let { " for $it" } ?: "") +
                            ". No match is not proof of non-execution; the ledger may omit entries.", target)
                        val rows = document.createElement("section").asDynamic(); target.appendChild(rows)
                        var offset = 0
                        fun renderEntries() {
                            rows.textContent = ""
                            for ((entryIndex, entry) in entries.drop(offset).take(16)) {
                                val item = document.createElement("details").asDynamic()
                                val summary = document.createElement("summary").asDynamic()
                                summary.textContent = "#${str(entry.ordinal)} · ${str(entry.nodeId)} · ${str(entry.type)} · ${str(entry.status)}" +
                                    if (entry.iteration == null) "" else " · iteration ${str(entry.iteration)}"
                                item.appendChild(summary); rows.appendChild(item)
                                text("Scope path: " + JSON.stringify(entry.path) + " · parent: " + str(entry.parent ?: "root") +
                                    " · iteration: " + str(entry.iteration ?: "none"), item)
                                val runtime = entry.runtime
                                if (objectValue(runtime)) {
                                    text("Guest observation · ${str(runtime.language)} · ${str(runtime.tier)}\nVM: ${str(runtime.vmId)}", item)
                                    text("Captured before release, not a live VM or proof of cleanup. Profiles count observed root returns, not all statements or breakpoints.", item)
                                    if (runtime.sourceScope == "graal-wrapper")
                                        text("Locations refer to the Graal invocation wrapper, not the authored JVM-language body.", item)
                                    details("Runtime counters", runtime.stats, item)
                                    val cuts = runtime.pointcuts
                                    if (objectValue(cuts) && cuts.kind == "root-profiles" && isArray(cuts.roots)) {
                                        text("${str(cuts.total)} root profiles · ${str(cuts.omitted)} omitted", item)
                                        for (root in arr(cuts.roots)) {
                                            val location = if (root.source == null) "location unavailable" else str(root.source) +
                                                (if (num(root.line) > 0) ":${str(root.line)}" else "") +
                                                (if (num(root.column) > 0) ":${str(root.column)}" else "")
                                            text("${str(root.root)} · ${str(root.calls)} returns · ${str(root.phase)}\n$location", item)
                                        }
                                    } else text("Root profiles unavailable for this VM tier; absence is not zero execution.", item)
                                } else if (entry.runtimeOmitted != null)
                                    text("Guest observation omitted: ${str(entry.runtimeOmitted)}. Absence is not zero execution.", item)
                                details("Invocation boundary JSON", entry, item)
                                button("Inspect invocation #${str(entry.ordinal)} in Confix", {
                                    if (current(ticket)) inspectContent(str(run.invocationsCid), arrayOf("entries", entryIndex))
                                }, item)
                            }
                            if (offset > 0) button("Previous invocations", { offset = maxOf(0, offset - 16); renderEntries() }, rows)
                            if (offset + 16 < entries.size) button("Next invocations", { offset += 16; renderEntries() }, rows)
                        }
                        renderEntries()
                    }
                } else text("No per-node invocation evidence was recorded for this receipt.", output)
                details("Consumed evidence and runtime bindings", objOf {
                    it.consumed = run.consumed; it.consumedTruncated = run.consumedTruncated
                    it.inputFingerprint = run.inputFingerprint
                    it.bindings = run.bindings; it.bindingsTruncated = run.bindingsTruncated
                })
                reference("Frozen program at invocation", str(run.programCid), ticket)
                for ((label, versions) in arrayOf("Pinned subprogram" to run.programVersions, "Stored prompt" to run.promptVersions)) {
                    if (objectValue(versions)) {
                        details("$label versions", versions)
                        val names = keysOf(versions)
                        if (names.size > 32) text("First 32 of ${names.size} $label references below; the immutable receipt JSON retains every version.", output)
                        for (name in names.take(32)) if (versions[name] != run.programCid)
                            reference("$label: $name", str(versions[name]), ticket)
                    }
                }
                if (run.previousReceiptCid != null) link("Previous state receipt", url(str(run.previousReceiptCid)), output)
                if (run.rebuildOf != null) link("Receipt this run rebuilt", url(str(run.rebuildOf)), output)
                if (comparison != null) try {
                    val request: dynamic = JSON.parse(inspectedRequest)
                    request.compareProgramCid = run.programCid
                    val checked = fetchJson("/api/lcnc/workspace", jsonInit("POST", JSON.stringify(request)))
                    if (!current(ticket)) return@launchJs
                    if (checked.error != null) error(str(checked.error))
                    require(checked.programCid == workspace.programCid && checked.comparison?.programCid == run.programCid) {
                        "Program comparison identity mismatch"
                    }
                    comparison.textContent = when (str(checked.comparison.relation)) {
                        "presentation" -> "The authored definition matches when presentation is excluded (node position, size, collapse and viewport). " +
                            "Exact program CIDs remain distinct. Inputs and dependencies were not compared."
                        "different" -> "The authored definition also differs. This receipt is not evidence for the edited definition."
                        else -> "Frozen-program comparison unavailable; no definition match is established."
                    }
                } catch (e: CancellationException) { throw e } catch (e: dynamic) {
                    if (current(ticket)) comparison.textContent = "Frozen-program comparison unavailable: " + failureText(e)
                }
            } catch (e: CancellationException) { throw e } catch (e: dynamic) {
                if (current(ticket)) output.textContent = "Receipt inspection failed: " + failureText(e)
            }
        }
    }
    button("Inspect receipt", { inspect(str(selected.value).trim()) }, section)
    if (latest?.receiptCid != null) {
        text("Latest explicit program run in this page", section)
        text(str(latest.status) + " · " + str(latest.runId), section)
        text("An inline run label does not establish named-program membership. Refreshing or reopening this view re-inspects the retained response snapshot.", section)
        button("Inspect latest explicit run", { inspect(str(latest.receiptCid), latest) }, section)
    }
    for (run in arr(workspace.runs)) {
        val membership = if (run.programKey == null) "Inline run · not saved-program history" else "Named program run"
        val version = when {
            truthy(run.sameVersion) -> "this version"
            run.programRelation == "presentation" -> "same definition · different presentation"
            else -> "different version"
        }
        text(membership + " · " + str(run.status) + " · " + str(run.runId) + " · " + version, section)
        if (run.receiptCid != null) button("Inspect " + str(run.runId), { inspect(str(run.receiptCid), run) }, section)
        else text("No durable receipt reference recorded.", section)
    }
    if (truthy(workspace.runComparisonLimited)) text("Inline discovery reached its ${str(workspace.runComparisonLimit)}-snapshot comparison limit. Older matching receipts may be absent; inspect their content IDs above.", section)
    if (arr(workspace.runs).isEmpty()) text("No matching recent runs found. A receipt content ID can still be inspected above.", section)
    section.appendChild(output)
    val retained = selectedReceipt
    if (retained?.receiptCid != null) inspect(str(retained.receiptCid), retained)
    else if (latest?.receiptCid != null) inspect(str(latest.receiptCid), latest)
}
