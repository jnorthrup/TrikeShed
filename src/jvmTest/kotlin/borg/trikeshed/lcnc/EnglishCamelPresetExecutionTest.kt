package borg.trikeshed.lcnc

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.forge.server.DocumentEnglishWire
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.module.*
import borg.trikeshed.nlp.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.*
import borg.trikeshed.vm.VmHost
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.test.*

/** Actual installed Camel engine and actual receipt query owner; parser annotations are labelled fixtures. */
class EnglishCamelPresetExecutionTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    private class Fixture(val ctx: ModuleContext, val owner: CompletableJob, val corpus: InMemoryProjectCorpus,
        val wire: DocumentEnglishWire, val camel: CamelRouteElement, val receipt: String)

    private fun parsed(text: String): NlpDocument {
        val words = Regex("[A-Za-z]+|[^\\sA-Za-z]").findAll(text).toList()
        val tokens = words.mapIndexed { i, word -> NlpToken(i + 1, word.range.first, word.range.last + 1,
            word.value, word.value.lowercase(), "NN", "O") }.toSeries()
        val edges = (0 until tokens.size).toSeries() α { i -> NlpDependency(if (i == 0) 0 else 1, i + 1, if (i == 0) "root" else "dep") }
        return NlpDocument(text, s_[NlpSentence(0, 0, text.length, tokens, edges)], NlpMetadata("fixture", "EnglishCamelPresetExecutionTest"))
    }

    private suspend fun fixture(name: String, parent: Job?): Fixture {
        check(GuestModules.isInstalled("camel")) { "Actual Camel module required: ./gradlew -p utils/subvm installCamel" }
        val files = JvmFileOperations(); val cas = FileCasStore(files, directory.resolve(name).toString())
        val owner = SupervisorJob(parent); val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch(name, store, cas), ReteNetwork(), ReteProductionRegistry(), null, null,
            ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas), ModuleRouteRegistry(), scope, { 123L }, directory.toFile())
        val corpus = InMemoryProjectCorpus(); val text = "Tenant may remain unless rent is not due."
        cas.put(text.encodeToByteArray()); corpus.put("legal", "agreement.txt", text.encodeToByteArray())
        val row = assertNotNull(corpus.document("legal", "agreement.txt"))
        val wire = DocumentEnglishWire(ctx, corpus) { NlpReader { parsed(it) } }; wire.attach()
        ctx.lcncRunners[SubVmLegos.CAMEL] = SubVmLegos.camel(VmHost.NONE)
        val camel = CamelRouteLegos.register(ctx)
        try {
            val response = assertNotNull(ctx.routes.match(DocumentEnglishWire.PATH)).route("POST", DocumentEnglishWire.PATH,
                jsonOf(_m["project" j row.project, "id" j row.id, "cid" j row.cid, "rev" j row.rev,
                    "seq" j row.seq, "begin" j 0, "end" j text.length]), null)!!
            assertEquals(200, response.status, response.body)
            return Fixture(ctx, owner, corpus, wire, camel, reifyMap(response.body)["receiptCid"] as String)
        } catch (failure: Throwable) {
            withContext(NonCancellable) { wire.drain(); camel.drain(); owner.cancelAndJoin() }
            throw failure
        }
    }

    private suspend fun Fixture.close() = withContext(NonCancellable) {
        wire.drain(); camel.drain(); owner.cancelAndJoin()
    }

    private fun runner(f: Fixture): LcncRunner = LcncRunner(PureNodes.registry { 123L } + f.ctx.lcncRunners +
        TribunalNodes.registry(TribunalDialog { _, _, _ -> error("Local English preset must not call a model") }).filterKeys { it == "display" })

    @Test fun actualLocalCamelCarriesExactReceiptIntoAnExplicitQueryDataset(): Unit = runBlocking {
        val f = fixture("english-camel-query", coroutineContext[Job])
        try {
            val program = EnglishLcncPreset.build(f.receipt)
            assertTrue(LcncTypeCheck.check(program, LcncContracts.all().associateBy { it.type }, strict = true).isEmpty())
            val result = runner(f).runProcedure(program)
            val dispatch = result.nodeOutputs.getValue("dispatch")
            val transport = dispatch["receipt"] as Map<*, *>
            assertEquals(f.receipt, dispatch["routed"]); assertEquals(f.receipt, transport["reply"])
            assertEquals(f.receipt, (transport["headers"] as Map<*, *>)[EnglishLcncPreset.HEADER])
            assertEquals("direct:english-evidence", transport["from"])
            val query = result.nodeOutputs.getValue("query")
            assertEquals("english-receipt-observations", query["dataset"])
            val source = query["source"] as Map<*, *>
            assertEquals(f.receipt, source["receiptCid"]); assertEquals("snapshot", source["mode"])
            assertEquals("unreviewed", source["reviewStatus"]); assertEquals("not-established", source["semanticAuthority"])
            val sparql = query["results"] as Map<*, *>
            val rows = ((sparql["results"] as Map<*, *>)["bindings"] as List<*>)
            assertEquals("not", (((rows.single() as Map<*, *>)["quote"] as Map<*, *>)["value"]))
            assertEquals(query["results"], result.nodeOutputs.getValue("bindings")["x"])
            assertEquals(query["source"], result.nodeOutputs.getValue("source")["x"])
            assertTrue(f.ctx.blackboard.snapshot().values.isEmpty())
        } finally { f.close() }
    }

    @Test fun routeCannotAcceptModuleOptionsOrChangedEvidenceIdentity(): Unit = runBlocking {
        val id = "sha256:" + "a".repeat(64)
        val registry = EnglishLcncPreset.registry()
        val plan = registry.getValue(EnglishLcncPreset.ROUTE)
        assertFailsWith<IllegalArgumentException> { plan.run(LcncNode("plan", EnglishLcncPreset.ROUTE, _m["module" j "camel-mail"]), _m["receiptCid" j id]) }
        assertFailsWith<IllegalArgumentException> { plan.run(LcncNode("plan", EnglishLcncPreset.ROUTE), _m["receiptCid" j id, "to" j "https://example.invalid/"]) }
        val value = plan.run(LcncNode("plan", EnglishLcncPreset.ROUTE), _m["receiptCid" j id])["route"]
        CamelRoutePlan.parse(value).validate(CamelLinkage.Reach.LOCAL, _l["camel"])
        assertFailsWith<IllegalArgumentException> {
            CamelRoutePlan("direct:english-evidence", s_[CamelRouteStep.To("smtp:example.invalid")])
                .validate(CamelLinkage.Reach.LOCAL, _l["camel", "camel-mail"])
        }
        val changed = "sha256:" + "b".repeat(64)
        assertFailsWith<IllegalArgumentException> {
            registry.getValue(EnglishLcncPreset.EVIDENCE).run(LcncNode("check", EnglishLcncPreset.EVIDENCE),
                _m["expectedCid" j id, "routed" j changed,
                    "transport" j _m["reply" j changed, "headers" j _m[EnglishLcncPreset.HEADER j id]]])
        }
    }

    @Test fun actualCorpusRunnerUsesItsPublishedInvocationKeyWithoutOptionalTopics(): Unit = runBlocking {
        val f = fixture("english-corpus-invocation", coroutineContext[Job])
        try {
            assertSame(LcncNodeKey.ENGLISH_QUERY, LcncNodeKey.of(EnglishObservationContract.TYPE))
            assertSame(LcncNodeKey.ENGLISH_CORPUS, LcncNodeKey.of(EnglishCorpusContract.TYPE))
            val contract = assertNotNull(LcncContracts.find(EnglishCorpusContract.TYPE))
            assertEquals("json", contract.outputKinds["topicCid"], "An omitted topic model has no topic CID")
            val corpus = assertNotNull(f.ctx.lcncRunners[EnglishCorpusContract.TYPE])
            val output = corpus.run(LcncNode("corpus", EnglishCorpusContract.TYPE),
                _m["receiptCids" j _l[f.receipt], "mode" j "current", "topics" j null])
            assertNull(output["topics"]); assertNull(output["topicCid"])
            assertTrue((output["occurrences"] as List<*>).isNotEmpty())
            val source = output["source"] as Map<*, *>
            assertEquals("english-receipt-corpus", source["selected"])
            assertEquals("unreviewed", source["reviewStatus"])
            assertTrue(f.ctx.blackboard.snapshot().values.isEmpty())
        } finally { f.close() }
    }

    @Test fun cancelledWaiterStillDrainsAnAdmittedActualCamelDispatch(): Unit = runBlocking {
        val f = fixture("english-camel-drain", coroutineContext[Job])
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val actual = CompletableDeferred<Map<String, Any?>>()
        try {
            // A test barrier controls admission timing; the dispatched runner still executes the real engine.
            val engine = SubVmLegos.camel(VmHost.NONE)
            val command = f.ctx.lcncRunners.getValue(SubVmLegos.CAMEL)
            withContext(f.ctx.scope.coroutineContext.minusKey(Job)) { f.camel.open() }
            val plan = EnglishLcncPreset.registry().getValue(EnglishLcncPreset.ROUTE)
                .run(LcncNode("plan", EnglishLcncPreset.ROUTE), _m["receiptCid" j f.receipt]).getValue("route")
            val admitted = async {
                f.camel.command {
                    entered.complete(Unit); release.await()
                    engine.run(LcncNode("dispatch", SubVmLegos.CAMEL, _m["trust" j "OWN", "reach" j "LOCAL", "module" j "camel", "body" j "english-evidence"]),
                        _m["route?" j plan]).also { actual.complete(it) }
                }
            }
            withTimeout(5000) { entered.await() }; admitted.cancelAndJoin()
            val draining = async { f.camel.drain() }; yield(); assertFalse(draining.isCompleted)
            release.complete(Unit); withTimeout(30_000) { draining.await() }
            assertEquals(f.receipt, actual.await()["routed"]); assertTrue(f.camel.closed.isCompleted)
            assertFailsWith<IllegalStateException> { command.run(LcncNode("closed", SubVmLegos.CAMEL), _m["route?" j plan]) }
        } finally { release.complete(Unit); f.close() }
    }
}
