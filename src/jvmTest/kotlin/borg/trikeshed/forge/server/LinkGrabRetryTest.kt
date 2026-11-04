package borg.trikeshed.forge.server

import borg.trikeshed.couch.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.htx.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.memory.*
import borg.trikeshed.narsese.ConstellationNodes
import borg.trikeshed.narsese.DecisionProvider
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class LinkGrabRetryTest {
    @Test
    fun failedFetchCanBeRetried(): Unit = runBlocking {
        val home = Files.createTempDirectory("link-retry").toFile()
        val cas = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(store, cas)
        val registry = ProjectDbRegistry("primary")
        val scopes = ProjectScopes(JvmFileOperations(), gateway,
            CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, store))), cas, null,
            projectDbs = registry, ledgerFile = File(home, "projects.tsv"), filesRoot = File(home, "files"))
        val project = scopes.beginUpload("project").name
        val corpus = JvmProjectCorpus(registry, scopes, ProjectMiner(registry, scopes, cas, null, File(home, "files")))
        val attempts = AtomicInteger()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val status = if (attempts.incrementAndGet() <= 2) 503 else 200
                val body = ByteSeries("<html><body>linked</body></html>".encodeToByteArray())
                return HtxExchangeResult(state.copy(response = HtxResponse(status, body, htxHeaders("Content-Type" j "text/html"))))
            }
        })
        val nodes = ConstellationNodes(home, ConfixBlackboard.empty(), scope = this, cas = cas)
        val tables = File(home, "constellations/books/root.html.jev").apply { mkdirs() }
        File(tables, "0.json").writeText("""{"links":[{"href":"http://localhost/child","referenced":true,"noul":1}]}""")
        val links = LinkGrab(nodes, corpus, scopes, htx)
        try {
            links.grab(project, "root.html", DecisionProvider.JEV)
            assertNull(corpus.document(project, "localhost/child.html"))
            links.grab(project, "root.html", DecisionProvider.JEV)
            assertNotNull(corpus.document(project, "localhost/child.html"))
            assertEquals(3, attempts.get())
            links.grab(project, "root.html", DecisionProvider.JEV)
            assertEquals(3, attempts.get(), "successfully landed work must remain deduplicated")
        } finally {
            nodes.drain()
            htx.close()
            scopes.unmount(project)
            scopes.drain()
            home.deleteRecursively()
        }
    }
}
