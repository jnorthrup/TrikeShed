package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException

/** Views of the existing canvas and daemon. Opening a facet never executes a graph. */
class PanelsFacets(val panels: Panels) {
    var drawer: dynamic = null
    var body: dynamic = null
    var revision = 0
    var tab = "confix"
    var offset = 0
    var path = "[]"
    var cursorPath = "[]"
    var cursorAxes = "[[2]]"
    var cursorSelections = emptyMap<Int, Int>()
    var cursorOffset = 0
    var groupOffset = 0
    var cursorCid: String? = null
    var storedId: String? = null
    var storedRev: String? = null
    var storageOffset = 0
    var storage: dynamic = null

    fun install() {
        val anchor = byId("rdfBtn") ?: return
        val button = document.createElement("button").asDynamic()
        button.id = "facetsBtn"; button.textContent = "Confix & facets"
        button.title = "Inspect this construction, use cases, languages and recorded evidence"
        anchor.parentNode.insertBefore(button, anchor)
        on(button, "click", { toggle() })
    }

    fun changed() {
        if (drawer != null && storedId == null) drawer.querySelector("[data-state]").textContent = "Canvas changed — refresh to inspect this version"
        revision++
        cursorPath = "[]"; cursorSelections = emptyMap(); cursorOffset = 0; groupOffset = 0; cursorCid = null
    }

    fun toggle() {
        if (drawer != null) { close(); return }
        val d = document.createElement("section").asDynamic(); drawer = d
        d.setAttribute("aria-label", "Workspace facets")
        d.style.cssText = "position:fixed;top:52px;right:12px;width:min(620px,calc(100vw - 24px));max-height:85vh;overflow:auto;z-index:55;background:var(--panel,#101620);border:1px solid var(--line,#263348);border-radius:10px;padding:16px;box-shadow:0 14px 42px #0008;font:12px/1.5 var(--mono,monospace)"
        d.innerHTML = "<header style='display:flex;justify-content:space-between;align-items:center'><strong>CONFIX & FACETS</strong>" +
            "<span><button data-refresh>Refresh</button> <button data-close aria-label='Close facets'>✕</button></span></header>" +
            "<div data-state style='color:var(--dim,#7f91a9);margin:8px 0'>Read-only inspection · no graph execution</div>" +
            "<nav data-tabs style='display:flex;gap:6px;flex-wrap:wrap;margin:12px 0'></nav><div data-body></div>"
        document.body!!.appendChild(d); body = d.querySelector("[data-body]")
        on(d.querySelector("[data-close]"), "click", { close() })
        on(d.querySelector("[data-refresh]"), "click", { show(tab) })
        show(tab)
    }

    fun close() { revision++; drawer?.remove(); drawer = null; body = null }

    fun show(selected: String) {
        tab = selected
        val serial = ++revision
        body.textContent = "Reading workspace…"
        launchJs {
            try {
                val catalog = fetchJson("/api/lcnc/workspace")
                if (serial != revision || drawer == null) return@launchJs
                if (truthy(catalog.error)) error(str(catalog.error))
                storage = catalog.storage
                if (selected == "confix" && storageOffset > 0 && truthy(storage?.available)) {
                    val page = fetchJson("/api/lcnc/workspace", jsonInit("POST", JSON.stringify(objOf {
                        it.source = objOf { s -> s.kind = "couch" }; it.offset = storageOffset; it.limit = 32
                    })))
                    if (serial != revision || drawer == null) return@launchJs
                    if (truthy(page.error)) error(str(page.error))
                    storage = page.storage
                }
                val nav = drawer.querySelector("[data-tabs]"); nav.innerHTML = ""
                for (facet in arr(catalog.facets)) {
                    val b = document.createElement("button").asDynamic()
                    b.textContent = facet.title; b.title = facet.description
                    b.setAttribute("aria-pressed", str(facet.id) == tab)
                    if (str(facet.id) == tab) b.style.borderColor = "var(--cyan,#57d3ff)"
                    nav.appendChild(b); on(b, "click", { offset = 0; show(str(facet.id)) })
                }
                when (selected) {
                    "language" -> languages(catalog)
                    "curation" -> recipes(serial)
                    else -> inspect(serial, selected)
                }
            } catch (e: CancellationException) { throw e } catch (e: dynamic) {
                if (serial == revision && body != null) {
                    body.textContent = "Inspection failed: " + str(e)
                    button("Reset inspection") {
                        path = "[]"; offset = 0; cursorAxes = "[[2]]"
                        changed(); show("confix")
                    }
                    if (storedId != null) button("Inspect canvas") {
                        storedId = null; storedRev = null; storageOffset = 0
                        path = "[]"; offset = 0; changed(); show("confix")
                    }
                }
            }
        }
    }

    suspend fun inspect(serial: Int, selected: String) {
        val request = objOf {
            it.name = str(panels.panelName().value).ifBlank { "canvas" }
            if (storedId != null && selected == "confix") it.source = objOf { s ->
                s.kind = "couch"; s.id = storedId; s.rev = storedRev
            } else it.document = panels.toConfix()
            it.path = JSON.parse(if (selected == "execution") "[]" else path); it.offset = offset; it.limit = 32
            it.cursor = objOf { q ->
                q.path = JSON.parse(if (selected == "execution") "[]" else cursorPath); q.axes = JSON.parse(cursorAxes)
                q.selections = (if (selected == "execution") emptyMap() else cursorSelections).map { entry -> arrayOf(entry.key, entry.value) }.toTypedArray()
                q.offset = cursorOffset; q.groupOffset = groupOffset; q.sourceCid = if (selected == "execution") null else cursorCid
            }
        }
        val r = fetchJson("/api/lcnc/workspace", jsonInit("POST", JSON.stringify(request)))
        if (serial != revision || drawer == null) return
        if (truthy(r.error)) error(str(r.error))
        drawer.querySelector("[data-state]").textContent = (if (r.source != null) "Stored Couch revision · " + str(r.source.rev)
            else "Inspected this canvas") + " · blackboard revision " + str(r.blackboardRevision)
        body.innerHTML = ""
        if (selected == "execution") {
            text("Program identity: " + str(r.programCid))
            nodeEvidence()
            for (binding in arr(r.bindings)) text(str(binding.id) + " · " + str(binding.type) + " · " + str(binding.binding) +
                "\nContext: " + str(binding.context.key ?: binding.context.role) +
                (if (arr(binding.requiredKeys).isEmpty()) "" else "\nServices: " + arr(binding.requiredKeys).joinToString(", ") { str(it) }))
            for (violation in arr(r.violations)) text("Type diagnostic: " + str(violation))
            receipts(r, serial)
            link("Run records", "/api/lcnc/runs?program=" + encodeURIComponent(str(request.name)))
            link("Shared blackboard", "/blackboard")
            pointcutEvidence(serial)
            link("Graal workspace · VM terminals · Hermes", "/graal")
            link("Document evidence and tuple sheets", "/documents")
            return
        }
        sources()
        if (r.source != null) {
            text(str(r.source.id) + " · revision " + str(r.source.rev) + " · sequence " + str(r.source.sequence))
            text("This is the indexed revision, not a claim that the live Couch head is unchanged. Inspecting it does not change the canvas.")
            if (truthy(r.source.deleted)) { text("Tombstone — this revision has no document body. Earlier revisions remain selectable."); return }
        }
        val c = r.confix
        cursorCid = str(c.cid)
        text(str(c.tokens) + " tokens · " + str(c.bytes) + " bytes · depth " + str(c.maxDepth) + "\n" + str(c.cid))
        val input = document.createElement("input").asDynamic(); input.value = path
        input.setAttribute("aria-label", "Confix path as a JSON array")
        input.style.cssText = "width:74%;box-sizing:border-box;margin:10px 4px 10px 0;padding:6px 8px;background:var(--bg,#090e16);color:var(--ink,#dce6f3);border:1px solid var(--line,#263348);border-radius:5px;font:inherit"; body.appendChild(input)
        button("Select path") {
            try { path = JSON.stringify(JSON.parse(str(input.value))); offset = 0; show("confix") }
            catch (e: dynamic) { panels.status("Use a JSON path array, for example [\"nodes\",0,\"params\"]") }
        }
        when {
            !truthy(c.found) -> { text("Path not found"); pre("—") }
            truthy(c.valueOmitted) -> text("Selected-value preview omitted · source size " + str(c.valueBytes) +
                " bytes · preview cap " + str(c.valueLimit) +
                "\nSelect a narrower path above or use cursor selection and traversal below.")
            else -> { text("Selected value"); pre(JSON.stringify(c.value, null, 2)) }
        }
        cursor(c.cursor)
        text("Flat token spans · these are not the row ordinals of the cursor above")
        val table = document.createElement("table").asDynamic(); table.style.cssText = "width:100%;text-align:left;font-size:11px"
        table.innerHTML = "<thead><tr><th>Token</th><th>Byte span</th><th>Kind</th><th>Depth</th><th>Children</th></tr></thead><tbody>" +
            arr(c.rows).joinToString("") { row -> "<tr><td>${escNullable(row.token)}</td><td>${escNullable(row.open)}…${escNullable(row.close)}</td>" +
                "<td>${escNullable(row.tag)}</td><td>${escNullable(row.depth)}</td><td>${escNullable(row.childCount)}</td></tr>" } + "</tbody>"
        body.appendChild(table)
        if (offset > 0) button("Previous tokens") { offset = maxOf(0, offset - 32); show("confix") }
        if (c.next != null) button("Next tokens") { offset = num(c.next).toInt(); show("confix") }
        if (r.source == null) {
            val violations = arr(r.violations)
            text(if (violations.isEmpty()) "No reported type violations. This is inspection, not an execution result."
                else "Type diagnostics\n" + violations.joinToString("\n") { str(it) })
        }
    }

    /** Page-owned attempt snapshots are not daemon receipts, current inputs, or VM breakpoints. */
    fun nodeEvidence() {
        text("Node inspection · last attempt in this tab. Refresh after running. These observations are not durable run receipts.")
        val nodes = panels.nodes()
        if (nodes.isEmpty()) { text("No nodes on this canvas."); return }
        val select = document.createElement("select").asDynamic()
        select.setAttribute("aria-label", "Inspect node execution")
        for (n in nodes) {
            val option = document.createElement("option").asDynamic()
            option.value = n.id; option.textContent = str(n.id) + " · " + str(n.type)
            select.appendChild(option)
        }
        body.appendChild(select)
        val output = document.createElement("section").asDynamic()
        output.setAttribute("aria-label", "Node execution observation"); body.appendChild(output)
        fun render() {
            output.textContent = ""
            val n = panels.node(select.value)
            val attempt = n?._lastAttempt
            if (attempt == null) { text("No attempt recorded in this tab.", output); return }
            text(str(attempt.state) + " · " + str(attempt.at), output)
            if (str(attempt.node) != JSON.stringify(executionNode(n)))
                text("Node changed since this attempt. The displayed inputs, source and result belong to the earlier version.", output)
            text("Node at invocation", output); pre(str(attempt.node), output)
            text("Wired inputs at invocation (parameter defaults remain in the node above)", output)
            pre(str(attempt.inputs), output)
            if (attempt.error != null) { text("Diagnostic", output); pre(str(attempt.error), output) }
            if (attempt.outputs != null) { text("Returned outputs", output); pre(str(attempt.outputs), output) }
        }
        on(select, "change", { render() }); render()
    }

    /** The existing pointcut plane is daemon-wide; never infer membership in this construction. */
    fun pointcutEvidence(serial: Int) {
        val section = document.createElement("section").asDynamic()
        section.setAttribute("aria-label", "Pointcut observations"); body.appendChild(section)
        text("Pointcuts · latest recorded value per site, daemon-wide. This is not the full trace, a breakpoint, or proof that this canvas executed.", section)
        val output = document.createElement("div").asDynamic()
        button("Inspect pointcuts", {
            output.textContent = "Reading recorded pointcuts…"
            launchJs {
                try {
                    val response = fetchJson("/api/graal/pointcuts")
                    if (serial != revision || drawer == null) return@launchJs
                    if (truthy(response.error)) error(str(response.error))
                    if (!js("Array.isArray(response.routes)").unsafeCast<Boolean>()) error("Pointcut route list unavailable")
                    output.textContent = ""
                    val routes = arr(response.routes)
                    val filter = document.createElement("input").asDynamic()
                    filter.setAttribute("aria-label", "Filter pointcut observations")
                    filter.placeholder = "Route, facet, class, method, property or source"
                    filter.style.cssText = "width:100%;box-sizing:border-box;font:inherit"
                    output.appendChild(filter)
                    val rows = document.createElement("div").asDynamic(); output.appendChild(rows)
                    var page = 0
                    fun render() {
                        rows.textContent = ""
                        val query = str(filter.value).trim().lowercase()
                        val matches = routes.filter { row ->
                            arrayOf(row.route, row.facet, row.className, row.methodName, row.property, row.sourcePath)
                                .any { str(it ?: "").lowercase().contains(query) }
                        }
                        text("${matches.size} matching / ${routes.size} recorded observations", rows)
                        for (row in matches.drop(page).take(32)) {
                            val details = document.createElement("details").asDynamic()
                            val summary = document.createElement("summary").asDynamic()
                            summary.textContent = str(row.route) + " · " + str(row.facet ?: "unknown facet")
                            details.appendChild(summary); rows.appendChild(details)
                            val site = if (row.bci != null && num(row.bci) < 0) "Guest property site: " else "Bytecode offset: "
                            text("Class: " + str(row.className ?: "not recorded") + "\nMethod: " +
                                str(row.methodName ?: "not recorded") + "\n" + site + str(row.bci ?: "not recorded"), details)
                            text("Property: " + str(row.property ?: "not recorded") + " · mark: " + str(row.mark ?: "not recorded"), details)
                            text(if (row.sourcePath != null) "Source: " + str(row.sourcePath) +
                                (if (row.line != null && num(row.line) > 0) ":" + str(row.line) else "") +
                                (if (row.column != null && num(row.column) > 0) ":" + str(row.column) else "")
                                else "Source location: not recorded", details)
                            pre(executionPreview(row.value), details)
                            link("Pointcut document", "/api/graal/doc?id=" + encodeURIComponent(str(row.route)), details)
                        }
                        if (matches.isEmpty()) text(if (routes.isEmpty()) "No pointcut landings recorded." else "No observations match this filter.", rows)
                        if (page > 0) button("Previous observations", { page = maxOf(0, page - 32); render() }, rows)
                        if (page + 32 < matches.size) button("Next observations", { page += 32; render() }, rows)
                    }
                    on(filter, "input", { page = 0; render() }); render()
                } catch (e: CancellationException) { throw e } catch (e: dynamic) {
                    if (serial == revision && drawer != null) output.textContent = "Pointcut inspection failed: " + str(e)
                }
            }
        }, section)
        section.appendChild(output)
    }

    fun sources() {
        text("Source · canvas or indexed Couch revision")
        val select = document.createElement("select").asDynamic()
        select.setAttribute("aria-label", "Confix source")
        select.style.cssText = "max-width:100%;font:inherit"
        val revisions = arr(storage?.revisions)
        fun option(value: String, label: String) {
            val o = document.createElement("option").asDynamic(); o.value = value; o.textContent = label; select.appendChild(o)
        }
        option("canvas", "Current canvas (unsaved edits included)")
        var selected = "canvas"
        revisions.forEachIndexed { i, entry ->
            option(i.toString(), str(entry.id) + " · " + str(entry.rev) + if (truthy(entry.deleted)) " · deleted" else "")
            if (storedId == str(entry.id) && storedRev == str(entry.rev)) selected = i.toString()
        }
        if (storedId != null && selected == "canvas") {
            option("pinned", storedId + " · " + storedRev + " (another page)"); selected = "pinned"
        }
        select.value = selected; body.appendChild(select)
        on(select, "change", {
            val value = str(select.value)
            if (value != "pinned") {
                val entry = value.toIntOrNull()?.let { revisions[it] }
                storedId = if (entry == null) null else str(entry.id)
                storedRev = if (entry == null) null else str(entry.rev)
                path = "[]"; offset = 0; changed(); show("confix")
            }
        })
        if (!truthy(storage?.available)) {
            text("Dense Couch indexing is unavailable on this host." + if (truthy(storage?.error)) "\n" + str(storage.error) else "")
            return
        }
        text(str(storage.indexedRevisions) + " indexed revisions / " + str(storage.sourceRevisions) +
            " Couch revisions. Indexing writes a separate dense projection; it does not replace Couch, run a graph, or change checkpoints.")
        if (storageOffset > 0) button("Newer revisions") { storageOffset = maxOf(0, storageOffset - 32); show("confix") }
        if (storage.next != null) button("Older revisions") { storageOffset = num(storage.next).toInt(); show("confix") }
        button("Index next 32 Couch revisions") {
            val serial = ++revision
            body.textContent = "Indexing one bounded batch…"
            launchJs {
                try {
                    val receipt = fetchJson("/api/lcnc/workspace", jsonInit("PUT", JSON.stringify(objOf {
                        it.action = "index-couch"; it.limit = 32
                    })))
                    if (serial != revision || drawer == null) return@launchJs
                    if (truthy(receipt.error)) error(str(receipt.error))
                    panels.status(str(receipt.imported) + " revisions indexed; " + str(receipt.remaining) + " remain. Couch source unchanged.")
                    storageOffset = 0; show("confix")
                } catch (e: CancellationException) { throw e } catch (e: dynamic) {
                    if (serial == revision && drawer != null) {
                        body.textContent = "Indexing failed: " + str(e) + ". Earlier indexed revisions remain available."
                        button("Inspect stored state") { show("confix") }
                    }
                }
            }
        }
    }

    fun cursor(c: dynamic) {
        text("Cursor scope " + cursorPath + " · " + str(c.selectedCount) + " / " + str(c.rowCount) + " rows\n" +
            "Child cursors keep their own row ordinals. Index selections intersect within this scope; no flattening or execution.")
        if (cursorPath != "[]") {
            button("Parent cursor") {
                cursorPath = JSON.stringify(arr(JSON.parse<dynamic>(cursorPath)).dropLast(1).toTypedArray())
                cursorSelections = emptyMap(); cursorOffset = 0; groupOffset = 0; show("confix")
            }
            button("Root cursor") {
                cursorPath = "[]"; cursorSelections = emptyMap(); cursorOffset = 0; groupOffset = 0; show("confix")
            }
        }
        text("Y indexes · columns 0=open, 1=close, 2=kind. Each array defines an independent composite-key index.")
        val axes = document.createElement("input").asDynamic(); axes.value = cursorAxes
        axes.setAttribute("aria-label", "Cursor index columns as JSON arrays")
        axes.style.cssText = "width:65%;box-sizing:border-box;font:inherit;padding:6px;background:var(--bg,#090e16);color:var(--ink,#dce6f3);border:1px solid var(--line,#263348);border-radius:5px"; body.appendChild(axes)
        button("Apply indexes") {
            try {
                cursorAxes = JSON.stringify(JSON.parse(str(axes.value)))
                cursorSelections = emptyMap(); cursorOffset = 0; groupOffset = 0; show("confix")
            } catch (e: dynamic) { panels.status("Use index column arrays, for example [[2],[0,1]]") }
        }
        arr(c.indexes).forEachIndexed { index, descriptor ->
            val label = document.createElement("label").asDynamic()
            label.textContent = "Index " + index + " · columns " + JSON.stringify(descriptor.columns) +
                " · " + str(descriptor.groupCount) + " groups "
            label.style.cssText = "display:block;margin:10px 0"
            val select = document.createElement("select").asDynamic()
            select.setAttribute("aria-label", "Select group for cursor index $index")
            fun option(value: Int, title: String) {
                val o = document.createElement("option").asDynamic(); o.value = value.toString(); o.textContent = title
                select.appendChild(o)
            }
            option(-1, "All groups")
            val current = cursorSelections[index]
            val groups = arr(descriptor.groups)
            if (current != null && groups.none { num(it.group).toInt() == current }) option(current, "Group $current (another page)")
            for (group in groups) option(num(group.group).toInt(), JSON.stringify(group.key) + " · " + str(group.count) + " rows")
            select.value = (current ?: -1).toString()
            label.appendChild(select); body.appendChild(label)
            on(select, "change", {
                val group = str(select.value).toInt()
                cursorSelections = if (group < 0) cursorSelections - index else cursorSelections + (index to group)
                cursorOffset = 0; show("confix")
            })
        }
        if (groupOffset > 0) button("Previous index groups") { groupOffset = maxOf(0, groupOffset - 32); show("confix") }
        if (arr(c.indexes).any { it.next != null }) button("Next index groups") { groupOffset += 32; show("confix") }
        val table = document.createElement("table").asDynamic(); table.style.cssText = "width:100%;text-align:left;font-size:11px"
        table.innerHTML = "<thead><tr><th>Source row</th><th>Byte span</th><th>Kind</th><th>Children</th></tr></thead><tbody>" +
            arr(c.rows).joinToString("") { row -> "<tr><td>${escNullable(row.ordinal)}</td><td>${escNullable(row.open)}…${escNullable(row.close)}</td>" +
                "<td>${escNullable(row.tag)}</td><td>${escNullable(row.childCount)}</td></tr>" } + "</tbody>"
        body.appendChild(table)
        if (arr(c.rows).isEmpty()) text("No rows in this selection.")
        for (row in arr(c.rows)) if (num(row.childCount) > 0 && arr(JSON.parse(cursorPath)).size < 32) {
            button("Children of row " + str(row.ordinal)) {
                cursorPath = JSON.stringify((arr(JSON.parse<dynamic>(cursorPath)).map { num(it).toInt() } + num(row.ordinal).toInt()).toTypedArray())
                cursorSelections = emptyMap(); cursorOffset = 0; groupOffset = 0; show("confix")
            }
        }
        if (cursorOffset > 0) button("Previous cursor rows") { cursorOffset = maxOf(0, cursorOffset - 32); show("confix") }
        if (c.next != null) button("Next cursor rows") { cursorOffset = num(c.next).toInt(); show("confix") }
    }

    fun languages(catalog: dynamic) {
        body.innerHTML = ""
        text("Typed functions receive input as data and return Confix values. Each invocation releases its guest. Wire value into Camel messages for a payload transform, or into Camel route to author endpoints and steps. Route definitions are data, not native Camel DSL.")
        text("Load a language-and-route construction for review to replace this canvas without running it. Undo restores the previous canvas. The local route carries data only; its review label is not an approval.")
        val runners = arr(catalog.runners).map { str(it) }
        for (language in arr(catalog.languages)) {
            text(str(language.label) + " · " + (if (truthy(language.available)) "available" else "unavailable") + "\n" + str(language.note))
            if (truthy(language.available) && "vm.polyglot" in runners) button("Place " + str(language.label) + " function") {
                place("vm.polyglot", language.params)
            }
            val construction = language.construction
            if (truthy(language.available) && truthy(construction?.document) && "vm.polyglot" in runners && "vm.camel" in runners)
                button("Load " + str(language.label) + " + Camel for review") {
                    panels.load(fromConfix(construction.document))
                    panels.panelName().value = str(construction.name).removePrefix("preset-")
                    panels.status(str(language.label) + " + Camel loaded without execution — edit the input or function, then choose Run")
                    close()
                }
            val route = language.routeConstruction
            if (truthy(language.available) && truthy(route?.document) && "vm.polyglot" in runners && "vm.camel" in runners)
                button("Author Camel route in " + str(language.label)) {
                    panels.load(fromConfix(route.document))
                    panels.panelName().value = str(route.name).removePrefix("preset-")
                    panels.status(str(language.label) + " route authoring loaded without execution — inspect endpoints and steps, then choose Run")
                    close()
                }
        }
        if ("vm.camel" in runners) button("Place Camel route") { place("vm.camel", obj()) }
        if ("vm.camel.catalog" in runners) button("Place Camel catalog") { place("vm.camel.catalog", obj()) }
        link("Graal workspace and terminals", "/graal")
    }

    suspend fun recipes(serial: Int) {
        val response = fetchJson("/api/panels/presets")
        if (serial != revision || drawer == null) return
        if (truthy(response.error)) error(str(response.error))
        body.innerHTML = ""
        text("Load a construction, inspect it, then choose Run. Loading here stops the old canvas sources; it does not run or store the new graph. Undo restores your previous construction.")
        for (recipe in arr(response.presets)) {
            text(str(recipe.title) + "\n" + str(recipe.does) + "\nNeeds: " + str(recipe.needs))
            button("Load for review") {
                if (truthy(recipe.document)) {
                    panels.load(fromConfix(recipe.document))
                    panels.panelName().value = str(recipe.name).removePrefix("preset-")
                    panels.status(str(recipe.title) + " loaded without execution — " + str(recipe.tweakFirst))
                    close()
                }
            }
        }
    }

    fun place(type: String, params: dynamic) {
        panels.addNode(type, (num(panels.viewport.clientWidth) / 2 - num(panels.view.x)) / num(panels.view.z),
            (num(panels.viewport.clientHeight) / 2 - num(panels.view.y)) / num(panels.view.z), params)
        panels.status("$type placed — inspect its parameters, then choose Run")
    }

    fun text(value: String, target: dynamic = body) {
        val p = document.createElement("p").asDynamic(); p.textContent = value
        p.style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere;margin:12px 0"; target.appendChild(p)
    }
    fun pre(value: String, target: dynamic = body) {
        val p = document.createElement("pre").asDynamic(); p.textContent = value
        p.style.cssText = "max-height:220px;overflow:auto;white-space:pre-wrap;background:var(--bg,#090e16);padding:10px;border-radius:6px"; target.appendChild(p)
    }
    fun button(label: String, action: () -> Unit) {
        button(label, action, body)
    }
    fun button(label: String, action: () -> Unit, target: dynamic) {
        val b = document.createElement("button").asDynamic(); b.textContent = label
        b.style.cssText = "margin:4px 6px 4px 0"; target.appendChild(b); on(b, "click", { action() })
    }
    fun link(label: String, href: String, target: dynamic = body) {
        val a = document.createElement("a").asDynamic(); a.textContent = label; a.href = href
        a.target = "_blank"; a.rel = "noopener"; a.style.cssText = "display:block;margin:8px 0;color:var(--cyan,#57d3ff)"; target.appendChild(a)
    }
}
