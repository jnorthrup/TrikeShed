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
            appendLine("props = new java.util.Properties()")
            appendLine("props.setProperty('annotators', '$annotators')")
            appendLine("pipeline = new edu.stanford.nlp.pipeline.StanfordCoreNLP(props)")
            appendLine("doc = new edu.stanford.nlp.pipeline.CoreDocument('$text')")
            appendLine("pipeline.annotate(doc)")
            appendLine("for (t in doc.tokens()) print(t.word() + '\\t' + t.tag() + '\\t' + t.lemma())")
        }
        evalInVm(host, node, facet, script, inputs)
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
            appendLine("props = new java.util.Properties()")
            appendLine("props.setProperty('annotators', '$effectiveAnnotators')")
            appendLine("pipeline = new edu.stanford.nlp.pipeline.StanfordCoreNLP(props)")
            appendLine("doc = new edu.stanford.nlp.pipeline.CoreDocument('$text')")
            appendLine("pipeline.annotate(doc)")
            appendLine("import edu.stanford.nlp.ling.CoreAnnotations")
            appendLine("import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations")
            appendLine("import edu.stanford.nlp.sentiment.SentimentCoreAnnotations")
            appendLine("import edu.stanford.nlp.ie.machinereading.structure.Span")
            appendLine("import com.google.gson.Gson")
            appendLine("import com.google.gson.reflect.TypeToken")
            appendLine("results = []")
            appendLine("for (sent in doc.sentences()) {")
            appendLine("  tokens = []")
            appendLine("  for (t in sent.tokens()) {")
            appendLine("    tok = [word: t.word(), tag: t.tag(), lemma: t.lemma(), index: t.index()]")
            appendLine("    ner = t.get(CoreAnnotations.NamedEntityTagAnnotation.class)")
            appendLine("    if (ner != null && ner != 'O') tok.ner = ner")
            appendLine("    nerBegin = t.get(CoreAnnotations.NamedEntityTagStartAnnotation.class)")
            appendLine("    nerEnd = t.get(CoreAnnotations.NamedEntityTagEndAnnotation.class)")
            appendLine("    if (nerBegin != null) tok.nerBegin = nerBegin")
            appendLine("    if (nerEnd != null) tok.nerEnd = nerEnd")
            appendLine("    tokens.add(tok)")
            appendLine("  }")
            appendLine("  deps = []")
            appendLine("  graph = sent.get(SemanticGraphCoreAnnotations.BasicDependenciesAnnotation.class)")
            appendLine("  if (graph != null) {")
            appendLine("    for (edge in graph.edgeListSorted()) {")
            appendLine("      deps.add([gov: edge.getGovernor().word(), dep: edge.getDependent().word(), rel: edge.getRelation().toString()])")
            appendLine("    }")
            appendLine("  }")
            appendLine("  sentObj = [text: sent.text(), tokens: tokens, deps: deps]")
            appendLine("  if ('$withSentiment' == 'true') {")
            appendLine("    tree = sent.get(SentimentCoreAnnotations.SentimentAnnotatedTree.class)")
            appendLine("    if (tree != null) {")
            appendLine("      sentObj.sentiment = tree.label().get(SentimentCoreAnnotations.SentimentClass.class)")
            appendLine("      sentObj.sentimentValue = tree.label().get(org.ejml.simple.SimpleMatrix)")
            appendLine("    }")
            appendLine("  }")
            appendLine("  nerSpans = []")
            appendLine("  seen = new HashSet()")
            appendLine("  for (t in sent.tokens()) {")
            appendLine("    ner = t.get(CoreAnnotations.NamedEntityTagAnnotation.class)")
            appendLine("    if (ner != null && ner != 'O') {")
            appendLine("      beginIdx = t.get(CoreAnnotations.NamedEntityTagStartAnnotation.class)")
            appendLine("      endIdx = t.get(CoreAnnotations.NamedEntityTagEndAnnotation.class)")
            appendLine("      if (beginIdx != null && endIdx != null) {")
            appendLine("        key = ner + ':' + beginIdx + '-' + endIdx")
            appendLine("        if (!seen.contains(key)) {")
            appendLine("          nerSpans.add([text: sent.tokens().subList(beginIdx - 1, endIdx).collect{it.word()}.join(' '), ner: ner, begin: beginIdx, end: endIdx])")
            appendLine("          seen.add(key)")
            appendLine("        }")
            appendLine("      }")
            appendLine("    }")
            appendLine("  }")
            appendLine("  if (nerSpans.size() > 0) sentObj.entities = nerSpans")
            appendLine("  results.add(sentObj)")
            appendLine("}")
            appendLine("gson = new Gson()")
            appendLine("print(gson.toJson(results))")
        }
        evalInVm(host, node, facet, script, inputs)
    }

    // ── camel: route DSL over the lego's params ─────────────────────────

    fun camel(host: borg.trikeshed.vm.VmHost) = LcncNodeRunner { node, inputs ->
        val facet = facetOf(node, default = "JVM")
        val from = node.params["from"] ?: "direct:lcnc"
        val to = node.params["to"] ?: "log:lcnc"
        val script = buildString {
            appendLine("ctx = new org.apache.camel.impl.DefaultCamelContext()")
            appendLine("ctx.addRoutes(new org.apache.camel.builder.RouteBuilder() {")
            appendLine("  void configure() {")
            appendLine("    from('$from').to('$to')")
            appendLine("  }")
            appendLine("})")
            appendLine("ctx.start()")
            appendLine("print('camel route up: $from → $to')")
        }
        evalInVm(host, node, facet, script, inputs)
    }

    // ── graalce: any Graal language, source spelled inline ──────────────

    fun graalce(host: borg.trikeshed.vm.VmHost) = LcncNodeRunner { node, inputs ->
        val facet = facetOf(node, default = "GRAAL_JS")
        val source = node.params["source"] ?: ""
        evalInVm(host, node, facet, source, inputs)
    }

    // ── shared eval path ────────────────────────────────────────────────

    private suspend fun evalInVm(
        host: borg.trikeshed.vm.VmHost,
        node: LcncNode,
        facetName: String,
        source: String,
        inputs: Map<String, Any?>,
    ): Map<String, Any?> {
        val facet = borg.trikeshed.vm.vmFacetOf(facetName)
            ?: throw IllegalArgumentException("vm lego '${node.id}': unknown facet '$facetName' (use JVM, GRAAL_JS, …)")
        val world = inputStrings(node, inputs, key = "world")
        val spec = borg.trikeshed.vm.VmSpec(
            id = "lcnc:${node.id}",
            facet = facet,
            trust = if (node.params["trust"] == "UNTRUSTED") borg.trikeshed.vm.VmTrust.UNTRUSTED else borg.trikeshed.vm.VmTrust.OWN,
            world = world,
        )
        val handle = host.get(spec.id) ?: host.spawn(spec)
        return try {
            val tele = handle.eval(source, node.id)
            mapOf(
                "facet" to facet.id,
                "vmId" to spec.id,
                "cid" to tele.cid.value,
                "text" to when (tele) {
                    is Teleported.Str -> tele.v
                    is Teleported.Bytes -> tele.v.decodeToString()
                    else -> tele.toString()
                },
                "inputs" to inputs.keys.toList(),
            )
        } finally {
            if (node.params["keep"] != "true") runCatching { handle.close() }
        }
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
