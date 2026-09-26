package borg.trikeshed.web.harness

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/** harness-arguments.js: invocation bindings per program and the resolved-binding receipt. */
object HarnessArguments {
    private val values = HashMap<String, LinkedHashMap<String, String>>()
    private val receipts = HashMap<String, dynamic>()
    var program: String? = null

    fun edits(name: String): LinkedHashMap<String, String> = values.getOrPut(name) { LinkedHashMap() }

    fun inputs(name: String): dynamic {
        val edits = edits(name)
        val out: dynamic = js("Object.create(null)")
        var size = 0
        InvocationLimits.checkCount(edits.size)
        for ((key, raw) in edits) {
            InvocationLimits.checkName(key)
            size += raw.length; InvocationLimits.checkSize(size)
            try { out[key] = JSON.parse(raw) } catch (_: Throwable) { throw IllegalArgumentException("Invalid JSON for argument $key") }
        }
        return out
    }

    fun open() {
        program = Harness.selected; if (program == null) return
        render(); byId("argumentInspector").asDynamic().showModal()
    }

    fun render() {
        val name = program!!
        val doc = Harness.document(name); val edits = edits(name)
        byId("argumentTitle").textContent = "$name arguments"
        val definitions = LinkedHashMap<String, dynamic>()
        for (n in (if (truthy(doc.nodes)) doc.nodes.unsafeCast<Array<dynamic>>() else emptyArray())) {
            if (n.type != "scope.in") continue
            val raw: dynamic = n.params?.name
            val key = if (jsTypeOf(raw) == "string") InvocationLimits.bindingName(raw as String) else null
            if (!key.isNullOrEmpty()) definitions[key] = if (truthy(n.params)) n.params else jsObject()
        }
        for (key in edits.keys) if (!definitions.containsKey(key)) definitions[key] = jsObject()
        val body = byId("argumentInputs"); body.replaceKids()
        for ((key, param) in definitions) {
            val row = el("tr"); val toggle = el("input").unsafeCast<HTMLInputElement>(); val input = el("input").unsafeCast<HTMLInputElement>()
            toggle.type = "checkbox"; toggle.checked = edits.containsKey(key); toggle.setAttribute("aria-label", "Supply $key")
            input.setAttribute("aria-label", "Value for $key"); input.spellcheck = false
            input.value = edits[key] ?: JSON.stringify(if (param.default == null) "" else param.default); input.disabled = !toggle.checked
            toggle.addEventListener("change", { input.disabled = !toggle.checked; if (toggle.checked) edits[key] = input.value else edits.remove(key); validate() })
            input.addEventListener("input", { edits[key] = input.value; validate() })
            val cells: List<Any> = listOf(toggle, key, if (truthy(param.kind)) jsString(param.kind) else "generic", input,
                if (hasOwn(param, "default")) JSON.stringify(param.default) else "unbound")
            for (value in cells) {
                val cell = el("td"); if (value is String) cell.textContent = value else cell.append(value.unsafeCast<HTMLElement>()); row.append(cell)
            }
            body.append(row)
        }
        validate(); renderReceipt()
    }

    fun validate(): Boolean {
        var error = ""
        try { inputs(program!!) } catch (e: Throwable) { error = errorMessage(e) }
        byId("argumentError").textContent = error
        byId("argumentRun").unsafeCast<HTMLButtonElement>().disabled =
            error.isNotEmpty() || Harness.running || Harness.dirty || Harness.selected != program || Harness.inspectionOnly()
        return error.isEmpty()
    }

    fun add() {
        val field = byId("argumentName").unsafeCast<HTMLInputElement>(); val name = field.value.trim()
        if (!InvocationLimits.acceptsNew(name, edits(program!!).containsKey(name))) return
        edits(program!!)[name] = "\"\""; field.value = ""; render()
    }

    private fun stamp(r: dynamic) = ReceiptOrder.Stamp(r.runId, jsNumber(r.timelineRevision), r.startedAtMs, jsNumber(r.startedAtMs), jsNumber(r.sequence))

    fun record(receipt: dynamic) {
        if (!truthy(receipt) || !truthy(receipt.program) || !truthy(receipt.programCid)) return
        val entry: dynamic = Harness.board[receipt.programKey]
        if (receipt.programCid != entry?.programCid) return
        val name = receipt.program as String
        val old = receipts[name]
        if (old != null && ReceiptOrder.keepsOld(stamp(old), stamp(receipt))) return
        receipts[name] = receipt
        if (program == name && byId("argumentInspector").asDynamic().open == true) renderReceipt()
    }

    fun renderReceipt() {
        val body = byId("argumentResolved"); body.replaceKids()
        val name = program ?: ""
        val receipt = receipts[name]; val entry = Harness.board[HarnessKeys.PROGRAM + name]
        val valid = truthy(receipt?.programCid) && receipt.programCid == entry?.programCid && !Harness.drafts.has(name)
        val status = byId("argumentReceipt")
        status.textContent = if (receipt == null) "No recorded invocation" else if (!valid) "Receipt belongs to another program version" else
            jsString(receipt.status) + " / " + jsString(receipt.runId) + (if (truthy(receipt.error)) " / " + jsString(receipt.error) else "") + (if (truthy(receipt.bindingsTruncated)) " / binding report truncated" else "")
        if (!valid) return
        val rows = ArrayList<dynamic>()
        for (r in (if (truthy(receipt.bindings)) receipt.bindings.unsafeCast<Array<dynamic>>() else emptyArray())) {
            val copy: dynamic = js("Object.assign({}, r)"); copy.node = r.nodeId; rows.add(copy)
        }
        val nodes = ArrayList<dynamic>()
        fun walk(ns: dynamic) { if (!truthy(ns)) return; for (n in ns.unsafeCast<Array<dynamic>>()) { nodes.add(n); walk(n.children) } }
        walk(entry.document?.nodes)
        for (n in nodes.filter { it.type == "ccek.incarnate" }) {
            val outputs: dynamic = receipt.outputs
            val args: dynamic = if (outputs == null) null else outputs[n.id]?.arguments
            for (r in (if (truthy(args)) args.unsafeCast<Array<dynamic>>() else emptyArray())) { val copy: dynamic = js("Object.assign({}, r)"); copy.node = n.id; rows.add(copy) }
        }
        for (r in rows.take(InvocationLimits.RESOLVED_ROWS)) {
            val row = el("tr")
            for (value in listOf<dynamic>(r.node, r.name, r.type, r.source, JSON.stringify(r.value), r.status)) {
                val cell = el("td"); cell.textContent = if (value == null) "" else jsString(value)
                if (truthy(r.overridden)) cell.title = "Overrides " + jsString(r.overridden.source) + ": " + JSON.stringify(r.overridden.value)
                row.append(cell)
            }
            body.append(row)
        }
    }
}
