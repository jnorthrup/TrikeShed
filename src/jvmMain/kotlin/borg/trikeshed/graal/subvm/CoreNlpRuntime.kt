package borg.trikeshed.graal.subvm

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.j
import borg.trikeshed.nlp.NlpDependency
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpMetadata
import borg.trikeshed.nlp.NlpReader
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpToken
import borg.trikeshed.vm.GuestModuleManifest
import java.lang.reflect.InvocationTargetException
import java.util.Properties

/** A serialized pipeline per reader; models and implementation stay in the managed module. */
class CoreNlpRuntime : NlpReader, AutoCloseable {
    private var bridge: Bridge? = null

    override suspend fun read(text: String): NlpDocument = analyze(text)

    @Synchronized
    override fun close() {
        bridge = null
    }

    @Synchronized
    fun analyze(text: String): NlpDocument {
        val loader = GuestModules.loaderFor(MODULE)
            ?: error("CoreNLP module is not installed; run ./gradlew -p utils/subvm installCorenlp")
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = loader
        return try {
            val current = bridge?.takeIf { it.loader === loader } ?: Bridge(loader, MODULE).also { bridge = it }
            current.analyze(text)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    companion object {
        const val MODULE = "corenlp"
    }

    private class Bridge(val loader: ClassLoader, module: String) {
        private val pipelineClass = loader.loadClass("edu.stanford.nlp.pipeline.StanfordCoreNLP")
        private val documentClass = loader.loadClass("edu.stanford.nlp.pipeline.CoreDocument")
        private val sentenceClass = loader.loadClass("edu.stanford.nlp.pipeline.CoreSentence")
        private val tokenClass = loader.loadClass("edu.stanford.nlp.ling.CoreLabel")
        private val graphClass = loader.loadClass("edu.stanford.nlp.semgraph.SemanticGraph")
        private val edgeClass = loader.loadClass("edu.stanford.nlp.semgraph.SemanticGraphEdge")
        private val indexedWordClass = loader.loadClass("edu.stanford.nlp.ling.IndexedWord")
        private val properties = Properties().apply {
            setProperty("annotators", "tokenize,ssplit,pos,lemma,depparse,ner")
            setProperty("threads", "1")
        }
        private val pipeline = pipelineClass.getConstructor(Properties::class.java).newInstance(properties)
        private val document = documentClass.getConstructor(String::class.java)
        private val annotate = pipelineClass.getMethod("annotate", documentClass)
        private val sentences = documentClass.getMethod("sentences")
        private val tokens = sentenceClass.getMethod("tokens")
        private val dependencyParse = sentenceClass.getMethod("dependencyParse")
        private val edges = graphClass.getMethod("edgeListSorted")
        private val roots = graphClass.getMethod("getRoots")
        private val governor = edgeClass.getMethod("getGovernor")
        private val dependent = edgeClass.getMethod("getDependent")
        private val relation = edgeClass.getMethod("getRelation")
        private val wordIndex = indexedWordClass.getMethod("index")
        private val index = tokenClass.getMethod("index")
        private val begin = tokenClass.getMethod("beginPosition")
        private val end = tokenClass.getMethod("endPosition")
        private val originalText = tokenClass.getMethod("originalText")
        private val lemma = tokenClass.getMethod("lemma")
        private val tag = tokenClass.getMethod("tag")
        private val ner = tokenClass.getMethod("ner")

        /**
         * What actually executed: the loaded processor class, the jar it resolved from, the
         * module manifest that pinned it, and the annotator properties handed to the constructor.
         * A version the jar's own manifest does not carry is left out rather than guessed.
         */
        private val metadata: NlpMetadata = NlpMetadata(
            processor = pipelineClass.name,
            implementation = CoreNlpRuntime::class.java.name,
            runtime = runtime(module),
            configuration = properties.stringPropertyNames().sorted().associateWith { properties.getProperty(it) },
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

        fun analyze(text: String): NlpDocument {
            val doc = document.newInstance(text)
            annotate.invoke(pipeline, doc)
            val rawSentences = sentences.invoke(doc) as List<*>
            val result = Array(rawSentences.size) { sentenceIndex ->
                val sentence = rawSentences[sentenceIndex]!!
                val rawTokens = tokens.invoke(sentence) as List<*>
                val tokenArray = Array(rawTokens.size) { i ->
                    val token = rawTokens[i]!!
                    NlpToken(
                        index.invoke(token) as Int,
                        begin.invoke(token) as Int,
                        end.invoke(token) as Int,
                        originalText.invoke(token) as String,
                        lemma.invoke(token) as? String ?: "",
                        tag.invoke(token) as? String ?: "",
                        ner.invoke(token) as? String ?: "O",
                    ).also {
                        check(it.begin >= 0 && it.end >= it.begin && it.end <= text.length) {
                            "CoreNLP returned an out-of-range text span"
                        }
                        check(it.word == text.substring(it.begin, it.end)) {
                            "CoreNLP originalText \"${it.word}\" does not match source span \"${text.substring(it.begin, it.end)}\""
                        }
                    }
                }
                val graph = dependencyParse.invoke(sentence)
                val rawEdges = edges.invoke(graph) as List<*>
                val rootIndices = (roots.invoke(graph) as Collection<*>).map { wordIndex.invoke(it) as Int }.sorted()
                val dependencyArray = Array(rootIndices.size + rawEdges.size) { i ->
                    if (i < rootIndices.size) NlpDependency(0, rootIndices[i], "root")
                    else {
                        val edge = rawEdges[i - rootIndices.size]!!
                        NlpDependency(
                            wordIndex.invoke(governor.invoke(edge)) as Int,
                            wordIndex.invoke(dependent.invoke(edge)) as Int,
                            relation.invoke(edge).toString(),
                        )
                    }
                }
                NlpSentence(
                    sentenceIndex,
                    tokenArray.firstOrNull()?.begin ?: 0,
                    tokenArray.lastOrNull()?.end ?: 0,
                    tokenArray.size j { i: Int -> tokenArray[i] },
                    dependencyArray.size j { i: Int -> dependencyArray[i] },
                )
            }
            return NlpDocument(text, result.size j { i: Int -> result[i] }, metadata)
        }
    }
}
