package borg.trikeshed.lcnc

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.vm.Teleported

/**
 * Sub-VM module legos: tika / corenlp / camel / graalce hosted as [LcncNodeRunner]s
 * over [VmSupervisor.current] — the host's own hypervisor, so a lego run IS a
 * supervised guest eval riding [borg.trikeshed.vm.VmHandle], receipts and all.
 *
 * Low-code discipline: a lego's parameters are Strings authored in the surface; its
 * inputs are the ring's own node outputs; its outputs land back into the ring's warm
 * base. Every lego is declared in [LcncContracts] (the one vocabulary), so the
 * concentric surface renders it with zero bespoke UI.
 *
 * Facet spelling is the enum NAME (`JVM`, `GRAAL_JS`, …) — resolved through
 * [vmFacetOf], which accepts either id (`java`) or name (`JVM`). Trust is OWN by
 * default; a UNTRUSTED lego degrades to ProcessIsolate per the host's policy.
 * `world` params seed the guest workspace (OWN only).
 */
object SubVmLegos {
    const val TIKA = "vm.tika"
    const val CORENLP = "vm.corenlp"
    const val CORENLP_EXTRACT = "vm.corenlp.extract"
    const val CAMEL = "vm.camel"
    const val GRAALCE = "vm.graalce"

    /**
     * Register every lego into the daemon's [ModuleContext.lcncRunners]. Called from
     * the daemon after ModuleContext assembly; no other site registers vm.* runners.
     */
    fun register(ctx: borg.trikeshed.module.ModuleContext) {
        val host = borg.trikeshed.vm.VmSupervisor.current
        ctx.lcncRunners[TIKA] = tika(host)
        ctx.lcncRunners[CORENLP] = corenlp(host)
        ctx.lcncRunners[CAMEL] = camel(host)
        ctx.lcncRunners[GRAALCE] = graalce(host)
        ctx.lcncRunners[CORENLP_EXTRACT] = corenlpExtract(host)
    }

    // ── tika: extract text + metadata from world-seeded files ─────────

    fun tika(host: borg.trikeshed.vm.VmHost) = LcncNodeRunner { node, inputs ->
        val facet = facetOf(node, default = "JVM")
        val files = inputStrings(node, inputs, key = "files")
            .ifEmpty { inputStrings(node, inputs, key = "text").let { if (it.isEmpty()) emptyList() else listOf("<text>") } }
        val script = buildString {
            appendLine("tika = Java.type('org.apache.tika.Tika')")
            for (f in files) appendLine("print(tika.parseToString(java.nio.file.Paths.get('/workspace/$f')))")
        }
        evalInVm(host, node, facet, script, inputs)
    }

    // ── corenlp: Stanford pipeline over a text lane ────────────────────

    fun corenlp(host: borg.trikeshed.vm.VmHost) = LcncNodeRunner { node, inputs ->
        val facet = facetOf(node, default = "JVM")
        val text = inputStrings(node, inputs, key = "text").joinToString("\n")
            .ifEmpty { node.params["text"] ?: "" }
        val annotators = node.params["annotators"] ?: "tokenize,ssplit,pos,lemma,depparse"
        val script = buildString {
            // GraalJS (not Groovy): Java.type for class literals, JSON.stringify for output.
            // Text rides as a global to avoid any quoting/escaping hazards.
            appendLine("var Properties = Java.type('java.util.Properties')")
            appendLine("var CoreNLP = Java.type('edu.stanford.nlp.pipeline.StanfordCoreNLP')")
            appendLine("var CoreDocument = Java.type('edu.stanford.nlp.pipeline.CoreDocument')")
            appendLine("var props = new Properties()")
            appendLine("props.setProperty('annotators', '${annotators.replace("'", "\\'")}')")
            appendLine("var pipeline = new CoreNLP(props)")
            appendLine("var doc = new CoreDocument(GUEST_TEXT)")
            appendLine("pipeline.annotate(doc)")
            appendLine("var out = []")
            appendLine("for (var i = 0; i < doc.tokens().size(); i++) {")
            appendLine("  var t = doc.tokens().get(i)")
            appendLine("  out.push(t.word() + '\\t' + t.tag() + '\\t' + t.lemma())")
            appendLine("}")
            // The joined payload is the eval VALUE (Teleported.Str) — print()
            // stays only as a human-readable terminal trace.
            appendLine("var RESULT = out.join('\\n')")
            appendLine("print(RESULT)")
            appendLine("RESULT")
        }
        evalInVmText(host, node, facet, script, text, inputs, defaultModule = "corenlp")
    }

    // ── corenlp.extract: NER + dependency + sentiment per sentence ───
    // Extends corenlp() to walk doc.sentences() and emit structured
    // JSON instead of flat token/tag/lemma lines.  The annotators string
    // defaults to include ner; sentiment is opt-in (param "sentiment"=true).

    fun corenlpExtract(host: borg.trikeshed.vm.VmHost) = LcncNodeRunner { node, inputs ->
        val facet = facetOf(node, default = "JVM")
        val text = inputStrings(node, inputs, key = "text").joinToString("\n")
            .ifEmpty { node.params["text"] ?: "" }
        val annotators = node.params["annotators"] ?: "tokenize,ssplit,pos,lemma,depparse,ner"
        val withSentiment = node.params["sentiment"] == "true"
        val effectiveAnnotators = if (withSentiment && !annotators.contains("sentiment")) {
            "$annotators,sentiment"
        } else annotators
        val script = buildString {
            // GraalJS: bare Java.type + plain object literals + JSON.stringify.
            // NER span begin/end annotations are CHAR offsets in CoreNLP; token
            // indexing is 1-based, so spans are recovered from the token list
            // rather than the (absent on CoreToken) begin/end annotations.
            appendLine("var Properties = Java.type('java.util.Properties')")
            appendLine("var CoreNLP = Java.type('edu.stanford.nlp.pipeline.StanfordCoreNLP')")
            appendLine("var CoreDocument = Java.type('edu.stanford.nlp.pipeline.CoreDocument')")
            appendLine("var CoreAnnotations = Java.type('edu.stanford.nlp.ling.CoreAnnotations')")
            appendLine("var props = new Properties()")
            appendLine("props.setProperty('annotators', '${effectiveAnnotators.replace("'", "\\'")}')")
            appendLine("var pipeline = new CoreNLP(props)")
            appendLine("var doc = new CoreDocument(GUEST_TEXT)")
            appendLine("pipeline.annotate(doc)")
            appendLine("var results = []")
            appendLine("for (var si = 0; si < doc.sentences().size(); si++) {")
            appendLine("  var sent = doc.sentences().get(si)")
            appendLine("  // CoreSentence typed accessors (not .get(annotation)): tokens(),")
            appendLine("  // dependencyParse(), sentiment() — direct, no annotation classes.")
            appendLine("  var tokens = []")
            appendLine("  var n = sent.tokens().size()")
            appendLine("  for (var i = 0; i < n; i++) {")
            appendLine("    var t = sent.tokens().get(i)")
            appendLine("    var tok = { word: t.word(), tag: t.tag(), lemma: t.lemma(), index: t.index() }")
            appendLine("    var ner = t.ner()")
            appendLine("    if (ner !== null && ner !== 'O') tok.ner = ner")
            appendLine("    tokens.push(tok)")
            appendLine("  }")
            appendLine("  var deps = []")
            appendLine("  try {")
            appendLine("    var graph = sent.dependencyParse()")
            appendLine("    var edges = graph.edgeListSorted()")
            appendLine("    for (var e = 0; e < edges.size(); e++) {")
            appendLine("      var edge = edges.get(e)")
            appendLine("      deps.push({ gov: edge.getGovernor().word(), dep: edge.getDependent().word(), rel: String(edge.getRelation()) })")
            appendLine("    }")
            appendLine("  } catch (depEx) { /* depparse not in the annotator set */ }")
            appendLine("  var sentObj = { text: String(sent.text()), tokens: tokens, deps: deps }")
            appendLine("  if ('$withSentiment' === 'true') {")
            appendLine("    try { sentObj.sentiment = String(sent.sentiment()) } catch (sEx) { }")
            appendLine("  }")
            appendLine("  // NER spans: contiguous runs of the same non-O tag over the 1-based tokens")
            appendLine("  var entities = []")
            appendLine("  var run = null")
            appendLine("  for (var i = 0; i < n; i++) {")
            appendLine("    var ner = tokens[i].ner || null")
            appendLine("    if (ner !== null && run !== null && run.ner === ner) { run.end = i + 1; }")
            appendLine("    else if (ner !== null) { if (run !== null) entities.push(run); run = { text: tokens[i].word, ner: ner, begin: i + 1, end: i + 1 }; }")
            appendLine("    else if (run !== null) { entities.push(run); run = null; }")
            appendLine("  }")
            appendLine("  if (run !== null) entities.push(run)")
            appendLine("  for (var r = 0; r < entities.length; r++) {")
            appendLine("    var words = []")
            appendLine("    for (var w = entities[r].begin - 1; w < entities[r].end; w++) words.push(tokens[w].word)")
            appendLine("    entities[r].text = words.join(' ')")
            appendLine("  }")
            appendLine("  if (entities.length > 0) sentObj.entities = entities")
            appendLine("  results.push(sentObj)")
            appendLine("}")
            appendLine("var RESULT = JSON.stringify(results)")
            appendLine("print(RESULT)")
            appendLine("RESULT")
        }
        evalInVmText(host, node, facet, script, text, inputs, defaultModule = "corenlp")
    }

    // ── camel: route DSL over the lego's params ─────────────────────────

    fun camel(host: borg.trikeshed.vm.VmHost) = LcncNodeRunner { node, inputs ->
        val facet = facetOf(node, default = "JVM")
        val from = node.params["from"] ?: "direct:lcnc"
        val to = node.params["to"] ?: "log:lcnc"
        // Body to dispatch through the route. Without one the lego only proves the context
        // starts; with one it proves the route actually carries a message end to end.
        val body = inputStrings(node, inputs, key = "body").joinToString("\n")
            .ifEmpty { node.params["body"] ?: "" }
        fun q(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
        val script = buildString {
            // GraalJS, not Groovy. The previous body was Java/Groovy source — bare `new
            // org.apache.camel...` and an anonymous `RouteBuilder(){ void configure() }` subclass —
            // which GraalJS cannot parse, so this lego could never have run even with Camel present.
            //
            // The corenlp legos above were converted out of that same Groovy shape by jnorthrup in
            // `wip` 49c94c868 (144+/64- here), which is where the "GraalJS (not Groovy)" note on
            // vm.corenlp comes from and why that lego runs. Camel was the one left behind — it had
            // no dependency to run against, so nothing forced the issue.
            // RouteBuilder is abstract and JS cannot subclass it, but Camel 4 exposes the static
            // RouteBuilder.addRoutes(CamelContext, LambdaRouteBuilder); LambdaRouteBuilder is a
            // functional interface, and GraalJS coerces a plain JS function to one.
            appendLine("var DefaultCamelContext = Java.type('org.apache.camel.impl.DefaultCamelContext')")
            appendLine("var RouteBuilder = Java.type('org.apache.camel.builder.RouteBuilder')")
            appendLine("var ctx = new DefaultCamelContext()")
            appendLine("RouteBuilder.addRoutes(ctx, function (rb) { rb.from('${q(from)}').to('${q(to)}') })")
            appendLine("ctx.start()")
            appendLine("var reply = null")
            if (body.isNotEmpty()) {
                // GUEST_TEXT carries the body as a JSON-quoted literal (see evalInVmText).
                appendLine("var tpl = ctx.createProducerTemplate()")
                appendLine("reply = String(tpl.requestBody('${q(from)}', GUEST_TEXT, Java.type('java.lang.String').class))")
            }
            appendLine("var names = []")
            appendLine("for (var i = 0; i < ctx.getRoutes().size(); i++) names.push(ctx.getRoutes().get(i).getId())")
            appendLine("var RESULT = JSON.stringify({ status: String(ctx.getStatus()), routes: names, from: '${q(from)}', to: '${q(to)}', reply: reply })")
            appendLine("ctx.stop()")
            appendLine("print(RESULT)")
            appendLine("RESULT")
        }
        evalInVmText(host, node, facet, script, body, inputs, defaultModule = "camel")
    }

    // ── graalce: any Graal language, source spelled inline ──────────────

    fun graalce(host: borg.trikeshed.vm.VmHost) = LcncNodeRunner { node, inputs ->
        val facet = facetOf(node, default = "GRAAL_JS")
        val source = node.params["source"] ?: ""
        evalInVm(host, node, facet, source, inputs)
    }

    // ── shared eval path ────────────────────────────────────────────────

    /**
     * Eval with an untrusted-length TEXT payload bound as the guest global
     * `GUEST_TEXT` (a `var` statement prepended to the source) instead of
     * string-literal-splicing it into the script. Documents contain quotes,
     * newlines, backslashes — splicing them into generated source is how the
     * old corenlp scripts broke; binding as a global leaves the script static
     * and the data as data.
     */
    private suspend fun evalInVmText(
        host: borg.trikeshed.vm.VmHost,
        node: LcncNode,
        facetName: String,
        script: String,
        text: String,
        inputs: Map<String, Any?>,
        defaultModule: String? = null,
    ): Map<String, Any?> {
        // JSON.stringify of the text is the one safe JS string literal form;
        // GraalJS parses it with no further interpretation.
        val literal = borg.trikeshed.parse.json.JsonSupport.stringify(text)
        return evalInVm(host, node, facetName, "var GUEST_TEXT = $literal;\n$script", inputs, defaultModule)
    }

    private suspend fun evalInVm(
        host: borg.trikeshed.vm.VmHost,
        node: LcncNode,
        facetName: String,
        source: String,
        inputs: Map<String, Any?>,
        defaultModule: String? = null,
    ): Map<String, Any?> {
        val facet = borg.trikeshed.vm.vmFacetOf(facetName)
            ?: throw IllegalArgumentException("vm lego '${node.id}': unknown facet '$facetName' (use JVM, GRAAL_JS, …)")
        val world = inputStrings(node, inputs, key = "world")
        // `module` names the guest classpath this lego needs (utils/subvm/<module>). An explicit
        // param wins so a canvas can point a lego at a different build of the same library.
        val module = node.params["module"] ?: defaultModule
        if (module != null && !borg.trikeshed.graal.subvm.GuestModules.isInstalled(module)) {
            // Fail HERE, with the command that fixes it, rather than as a ClassNotFoundException
            // thrown from inside a guest script where the cause is invisible.
            throw IllegalStateException(
                "vm lego '${node.id}': guest module '$module' is not installed. " +
                    "Run: ./gradlew -p utils/subvm install${module.replaceFirstChar { it.uppercase() }}" +
                    (borg.trikeshed.graal.subvm.GuestModules.root()?.let { " (modules root: $it)" }
                        ?: " (no utils/subvm directory found from ${System.getProperty("user.dir")})"),
            )
        }
        val spec = borg.trikeshed.vm.VmSpec(
            id = "lcnc:${node.id}",
            facet = facet,
            trust = if (node.params["trust"] == "UNTRUSTED") borg.trikeshed.vm.VmTrust.UNTRUSTED else borg.trikeshed.vm.VmTrust.OWN,
            world = world,
            module = module,
        )
        val handle = host.get(spec.id) ?: host.spawn(spec)
        return try {
            val tele = handle.eval(source, node.id)
            // The eval VALUE is the lego's structured result — the scripts end
            // with the payload expression (print() is only a human trace on the
            // VM's xterm, lossy past 28 rows). Terminal text is the FALLBACK for
            // legacy scripts whose last expression is null.
            val terminalText = runCatching {
                val session = hostTerminalSession(host, spec.id) ?: return@runCatching null
                // Screen (28 rows) + scrollback (up to 200): a token dump longer
                // than the screen scrolls, so the head of the output lives in
                // scrollback. Reconstruct print order scrollback→screen.
                val term = session.panel.terminal
                val snap = term.snapshot(500)
                val sb = (0 until snap.scrollback.size).joinToString("\n") { r ->
                    val line = snap.scrollback[r]
                    (0 until line.size).joinToString("") { cIdx -> line[cIdx].text }
                }
                sb + "\n" + term.plainText()
            }.getOrNull()
            mapOf(
                "facet" to facet.id,
                "vmId" to spec.id,
                "cid" to tele.cid.value,
                "text" to when {
                    tele is Teleported.Str -> tele.v
                    tele is Teleported.Bytes -> tele.v.decodeToString()
                    else -> extractPrintedOutput(terminalText, spec.id) ?: tele.toString()
                },
                "inputs" to inputs.keys.toList(),
            )
        } finally {
            if (node.params["keep"] != "true") runCatching { handle.close() }
        }
    }

    /** The terminal session a spawn opened for this vm id — where guest print() lands. */
    private fun hostTerminalSession(host: borg.trikeshed.vm.VmHost, vmId: String): borg.trikeshed.vm.VmTerminalSession? {
        val terminals = (host as? borg.trikeshed.vm.HypervisorVmHost)?.terminals ?: return null
        return terminals[vmId]
    }

    /**
     * Peel the printed output off the terminal screen: everything after the
     * last line that looks like the session's echo of our eval (the prompt /
     * banner prefaces it). When nothing printed, null → fall back to the
     * eval's Teleported value.
     */
    private fun extractPrintedOutput(terminalText: String?, vmId: String): String? {
        if (terminalText.isNullOrBlank()) return null
        val lines = terminalText.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        // The banner line contains the vm id ("lcnc:n1 · js · in-process").
        val bannerIdx = lines.indexOfFirst { it.contains(vmId) }
        val body = if (bannerIdx >= 0) lines.drop(bannerIdx + 1) else lines
        val cleaned = body
            .filterNot { it.startsWith("lcnc:") }
            .joinToString("\n")
            .trim()
        return cleaned.ifEmpty { null }
    }

    // ── param/input spelling helpers ────────────────────────────────────

    private fun facetOf(node: LcncNode, default: String): String = node.params["facet"] ?: default

    /** Input lane: wire-carried values win; the `in:`-prefixed param is the fallback spelling. */
    private fun inputStrings(node: LcncNode, inputs: Map<String, Any?>, key: String): List<String> {
        val wired = inputs[key]
        if (wired != null) return listOf(wired.toString())
        val param = node.params["in:$key"] ?: return emptyList()
        return param.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
