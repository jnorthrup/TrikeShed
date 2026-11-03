package borg.trikeshed.forge.server

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.htx.*
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.memory.*
import borg.trikeshed.module.*
import borg.trikeshed.narsese.ConstellationNodes
import borg.trikeshed.narsese.DecisionProvider
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CurationInstalledLinkTest {
    @Test
    fun installedLinkTraversalAdmitsItsLandedDocumentAfterDrainStarts(): Unit = runBlocking {
        val home = Files.createTempDirectory("installed-link-intake").toFile()
        val cas = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(store, cas)
        val registry = ProjectDbRegistry("primary")
        val scopes = ProjectScopes(JvmFileOperations(), gateway,
            CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, store))), cas, null,
            projectDbs = registry, ledgerFile = File(home, "projects.tsv"), filesRoot = File(home, "files"))
        val project = scopes.beginUpload("project").name
        val miner = ProjectMiner(registry, scopes, cas, null, File(home, "files"))
        val corpus = JvmProjectCorpus(registry, scopes, miner)
        val fetchStarted = CompletableDeferred<Unit>()
        val fetchRelease = CompletableDeferred<Unit>()
        val curateStarted = CompletableDeferred<Unit>()
        val curateRelease = CompletableDeferred<Unit>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                fetchStarted.complete(Unit)
                fetchRelease.await()
                val body = ByteSeries("<html><body>linked</body></html>".encodeToByteArray())
                return HtxExchangeResult(state.copy(response = HtxResponse(200, body, htxHeaders("Content-Type" j "text/html"))))
            }
        })
        val blackboard = ConfixBlackboard.empty()
        val ctx = ModuleContext(Couch("installed-link", store, cas), ReteNetwork(), ReteProductionRegistry(),
            null, null, blackboard, cas, gateway, ModuleRouteRegistry(), this, { 123L }, home, htx)
        ctx.lcncRunners[ConstellationNodes.CURATE] = LcncNodeRunner { _, _ ->
            curateStarted.complete(Unit); curateRelease.await(); _m["book" j "root.html"]
        }
        ctx.lcncRunners[ConstellationNodes.JOIN] = LcncNodeRunner { _, _ -> emptyMap() }
        val nodes = ConstellationNodes(home, blackboard, scope = this, cas = cas)
        val tables = File(home, "constellations/books/root.html.jev").apply { mkdirs() }
        File(tables, "0.json").writeText("""{"links":[{"href":"http://localhost/child","referenced":true,"noul":1}]}""")
        val intake = CurationIntake.install(nodes, ctx, corpus, scopes, this)
        try {
            scopes.uploadPut(project, "root.html", "<html><body>root</body></html>".encodeToByteArray(), null)
            withTimeout(5_000) { curateStarted.await() }
            // Invoke the installed traversal without running a real remote model; its
            // LinkGrab, HTTP route, ProjectScopes write and callback are all real.
            val field = CurationIntake::class.java.getDeclaredField("grab").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val traverse = field.get(intake) as (suspend (CurationIntake.Doc) -> Unit)
            val traversal = async { traverse(CurationIntake.Doc(project, "root.html", DecisionProvider.JEV)) }
            withTimeout(5_000) { fetchStarted.await() }
            val draining = async { intake.drain() }
            yield()
            fetchRelease.complete(Unit)
            withTimeout(5_000) { traversal.await() }
            assertNotNull(corpus.document(project, "localhost/child.html"))
            curateRelease.complete(Unit)
            withTimeout(10_000) { draining.await() }
            val tally = intake.status().getValue(project) as Map<*, *>
            val providers = tally["providers"] as Map<*, *>
            val jev = providers[DecisionProvider.JEV.id] as Map<*, *>
            assertEquals(1, jev["queued"], "the installed traversal must use internal admission")
            assertEquals(0, jev["pending"])
        } finally {
            fetchRelease.complete(Unit)
            curateRelease.complete(Unit)
            intake.drain()
            nodes.drain()
            htx.close()
            scopes.unmount(project)
            scopes.drain()
            home.deleteRecursively()
        }
    }
}
