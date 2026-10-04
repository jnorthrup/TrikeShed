package borg.trikeshed.graal.subvm

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpMention
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpMetadata
import borg.trikeshed.nlp.NlpReader
import borg.trikeshed.vm.GuestModuleManifest
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyMap

/**
 * A reader over the one pipeline, which lives in a Graal isolate: an [InProcessIsolate] on the JVM facet
 * with the `corenlp` guest module mounted as its classpath, so `edu.stanford.nlp.*` resolves inside the
 * isolate and nowhere else in the daemon. The pipeline is built once in the guest; each text crosses as
 * a string and the document comes back in [NlpWire]'s positional shape. A text parsed before is answered
 * from a bounded cache, so curation passes that re-read the same sections cost no parse.
 */
class CoreNlpRuntime : NlpReader, AutoCloseable {
    override suspend fun read(text: String): NlpDocument = analyze(text)

    /** The pipeline is shared; a reader holds nothing to release. */
    override fun close() {}

    fun analyze(text: String): NlpDocument = Companion.analyze(text)

    companion object {
        const val MODULE = "corenlp"
        /** Cache bound, in characters of cached text. */
        const val CACHED_CHARS = 8 shl 20

        /**
         * natlog + openie read every clause as (subject; relation; object), not only modal ones; kbp is
         * Stanford relation extraction: typed slot relations between named mentions. Statistical coref is
         * quadratic in mentions and stalled a treatise section for 30+ minutes; fast neural coref costs ~19%
         * on treatise sections for ~3% more clauses, a third of them misread (pleonastic `it`); it is off.
         * kbp rebuilds the sentence's graph once per mention pair, so a statute's 1,100-character enumeration
         * held one section for 11+ CPU minutes; kbp reads sentences of at most 100 tokens.
         */
        val CONFIGURATION = mapOf("annotators" to "tokenize,ssplit,pos,lemma,depparse,ner,natlog,openie,kbp", "threads" to "1", "kbp.maxlen" to "100")

        private var cachedChars = 0L
        private val parsed = object : LinkedHashMap<String, NlpDocument>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NlpDocument>): Boolean =
                (cachedChars > CACHED_CHARS).also { if (it) cachedChars -= eldest.key.length }
        }

        /**
         * The most text one parse takes. CoreNLP's per-document annotators (the entity matcher above all)
         * grow past linear in document length, and a 340k-character treatise section exhausted a 24 GB heap;
         * a longer text is parsed in paragraph-bounded pieces and stitched into one document.
         */
        const val PARSE_CHARS = 16_384

        /** Texts at most this long (a question, a premise) parse in their own isolate, never behind a book's section. */
        const val ASK_CHARS = 2_048

        /** One isolate and the parses it serializes. */
        private class Lane { var guest: Guest? = null }
        private val books = Lane()
        private val asks = Lane()

        private fun analyze(text: String): NlpDocument {
            synchronized(parsed) { parsed[text] }?.let { return it }
            val lane = if (text.length <= ASK_CHARS) asks else books
            val doc = synchronized(lane) {
                val loader = GuestModules.loaderFor(MODULE)
                    ?: error("CoreNLP module is not installed; run ./gradlew -p utils/subvm installCorenlp")
                val g = lane.guest?.takeIf { it.loader === loader && it.isolate.isAlive } ?: Guest(loader).also { lane.guest = it }
                if (text.length <= PARSE_CHARS) g.analyze(text) else stitch(text, pieces(text).map { r -> r.first to g.analyze(text.substring(r.first, r.last + 1)) })
            }
            synchronized(parsed) { cachedChars += text.length; parsed[text] = doc }
            return doc
        }

        /** Cuts of at most [PARSE_CHARS]: at the last paragraph break, else the last sentence end, else the last space. */
        internal fun pieces(text: String): List<IntRange> {
            val out = ArrayList<IntRange>()
            var from = 0
            while (from < text.length) {
                var to = minOf(text.length, from + PARSE_CHARS)
                if (to < text.length) {
                    val floor = from + PARSE_CHARS / 4
                    to = sequenceOf(text.lastIndexOf("\n\n", to), text.lastIndexOf(". ", to).let { if (it < 0) it else it + 1 }, text.lastIndexOf(' ', to))
                        .firstOrNull { it > floor }?.let { it + 1 } ?: to
                }
                out.add(from until to)
                from = to
            }
            return out
        }

        /** One document from pieces parsed apart: token offsets and sentence ordinals shifted onto the whole text. */
        internal fun stitch(text: String, parts: List<Pair<Int, NlpDocument>>): NlpDocument {
            val sentences = ArrayList<NlpSentence>()
            val mentions = ArrayList<NlpMention>()
            for ((off, d) in parts) {
                val base = sentences.size
                for (k in 0 until d.sentences.size) {
                    val s = d.sentences[k]
                    val toks = Array(s.tokens.size) { i -> s.tokens[i].let { it.copy(begin = it.begin + off, end = it.end + off) } }
                    sentences.add(s.copy(index = base + k, begin = s.begin + off, end = s.end + off, tokens = toks.size j { toks[it] }))
                }
                d.mentions.mapTo(mentions) { it.copy(sentence = it.sentence + base) }
            }
            return NlpDocument(text, sentences.size j { sentences[it] }, parts.firstOrNull()?.second?.metadata, mentions)
        }
    }

    /** The isolate and what the host reports of it: the processor class and the jar it resolved from. */
    private class Guest(val loader: ClassLoader) {
        val isolate = InProcessIsolate("corenlp", borg.trikeshed.pointcut.VmFacet.JVM, Budget(statements = 0, wallMillis = 0), guestModule = MODULE)
        private val pipelineClass = loader.loadClass("edu.stanford.nlp.pipeline.StanfordCoreNLP")

        init { withLoader { isolate.eval(SCRIPT.replace("\$CONFIGURATION", jsonOf(CONFIGURATION)), "corenlp.js") } }

        private inline fun <T> withLoader(block: () -> T): T {
            val previous = Thread.currentThread().contextClassLoader
            Thread.currentThread().contextClassLoader = loader
            return try { block() } finally { Thread.currentThread().contextClassLoader = previous }
        }

        fun analyze(text: String): NlpDocument {
            val wire = withLoader { isolate.call("parse", borg.trikeshed.vm.Teleported.Str(text)) }
            val m = reifyMap((wire as? borg.trikeshed.vm.Teleported.Str)?.v ?: error("corenlp isolate returned $wire"))
            val doc = NlpWire.decode(m)
            for (k in 0 until doc.sentences.size) for (i in 0 until doc.sentences[k].tokens.size) {
                val t = doc.sentences[k].tokens[i]
                check(t.begin >= 0 && t.end >= t.begin && t.end <= text.length) { "CoreNLP returned an out-of-range text span" }
                check(t.word == text.substring(t.begin, t.end)) {
                    "CoreNLP originalText \"${t.word}\" does not match source span \"${text.substring(t.begin, t.end)}\""
                }
            }
            return NlpDocument(doc.text, doc.sentences, metadata, doc.mentions)
        }

        /**
         * What actually executed: the loaded processor class, the jar it resolved from, the
         * module manifest that pinned it, and the annotator properties handed to the constructor.
         * A version the jar's own manifest does not carry is left out rather than guessed.
         */
        private val metadata: NlpMetadata = NlpMetadata(
            processor = pipelineClass.name,
            implementation = CoreNlpRuntime::class.java.name,
            runtime = runtime(MODULE),
            configuration = CONFIGURATION.toSortedMap(),
        )

        private fun runtime(module: String): Map<String, String> {
            val manifestText = GuestModules.manifestFile(module)?.readText()
            val manifest = manifestText?.let { GuestModuleManifest.parse(it, fallbackModule = module) } ?: GuestModuleManifest(module)
            val jar = pipelineClass.protectionDomain?.codeSource?.location?.path?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
            val pinned = jar?.let { name -> manifest.entries.firstOrNull { it.file == name } }
            val pkg = pipelineClass.`package`
            return buildMap {
                put("module", manifest.module)
                manifest.parent?.let { put("parent", it) }
                if (manifest.declared.isNotEmpty()) put("declared", manifest.declared.joinToString(","))
                manifestText?.let { put("manifestCid", ContentId.of(it.encodeToByteArray()).value) }
                jar?.let { put("jar", it) }
                pinned?.let { put("jarSha256", it.sha256) }
                pkg?.implementationTitle?.let { put("packageTitle", it) }
                pkg?.implementationVersion?.let { put("packageVersion", it) }
            }
        }
    }
}

/**
 * The guest side: the pipeline built once from the isolate's own classpath, and `parse(text)` answering
 * one document as JSON in [NlpWire]'s positional rows — sentences of tokens, dependencies (roots first,
 * governor 0), OpenIE triples and KBP triples as one-based token spans with their glosses.
 */
private val SCRIPT = """
var Properties = Java.type('java.util.Properties');
var StanfordCoreNLP = Java.type('edu.stanford.nlp.pipeline.StanfordCoreNLP');
var CoreDocument = Java.type('edu.stanford.nlp.pipeline.CoreDocument');
var TRIPLES = Java.type('edu.stanford.nlp.naturalli.NaturalLogicAnnotations${'$'}RelationTriplesAnnotation').class;
var KBP = Java.type('edu.stanford.nlp.ling.CoreAnnotations${'$'}KBPTriplesAnnotation').class;
var props = new Properties();
var conf = JSON.parse('${'$'}CONFIGURATION');
for (var k in conf) props.setProperty(k, conf[k]);
var pipeline = new StanfordCoreNLP(props);
function span(l) { return l.isEmpty() ? [1, 0] : [l.get(0).index(), l.get(l.size() - 1).index()]; }
function relations(cm, key) {
  var out = [], c = cm.get(key);
  if (c == null) return out;
  for (var it = c.iterator(); it.hasNext();) {
    var t = it.next(), s = span(t.subject), r = span(t.relation), o = span(t.object);
    out.push([s[0], s[1], r[0], r[1], o[0], o[1], t.subjectGloss(), t.relationGloss(), t.objectGloss(), t.confidence]);
  }
  return out;
}
function parse(text) {
  var doc = new CoreDocument(text);
  pipeline.annotate(doc);
  var ss = doc.sentences(), out = [];
  for (var k = 0; k < ss.size(); k++) {
    var s = ss.get(k), ts = s.tokens(), toks = [];
    for (var i = 0; i < ts.size(); i++) {
      var t = ts.get(i);
      toks.push([t.index(), t.beginPosition(), t.endPosition(), t.originalText(), t.lemma() || '', t.tag() || '', t.ner() || 'O']);
    }
    var g = s.dependencyParse(), deps = [], roots = [];
    for (var ri = g.getRoots().iterator(); ri.hasNext();) roots.push(ri.next().index());
    roots.sort(function (a, b) { return a - b; });
    for (var j = 0; j < roots.length; j++) deps.push([0, roots[j], 'root']);
    var es = g.edgeListSorted();
    for (var j = 0; j < es.size(); j++) { var e = es.get(j); deps.push([e.getGovernor().index(), e.getDependent().index(), e.getRelation().toString()]); }
    var cm = s.coreMap();
    out.push([k, toks.length ? toks[0][1] : 0, toks.length ? toks[toks.length - 1][2] : 0, toks, deps, relations(cm, TRIPLES), relations(cm, KBP)]);
  }
  return JSON.stringify({ text: text, sentences: out, mentions: [] });
}
"""
