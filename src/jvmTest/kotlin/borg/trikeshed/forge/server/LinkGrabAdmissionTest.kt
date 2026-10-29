package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.htx.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.memory.*
import borg.trikeshed.narsese.ConstellationNodes
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class LinkGrabAdmissionTest {
    @Test
    fun pdfAdmissionRejectsHtmlBeforeAnyRetentionAndKeepsRedirectProvenance(): Unit = runBlocking {
        val home = Files.createTempDirectory("pdf-admission").toFile()
        val cas = CasStore.inMemory()
        val couch = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(couch, cas)
        val registry = ProjectDbRegistry("trikeshed")
        val scopes = ProjectScopes(JvmFileOperations(), gateway,
            CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, couch))), cas, null,
            projectDbs = registry, ledgerFile = home.resolve("mount-ledger.tsv"), filesRoot = home.resolve("files"))
        val corpus = JvmProjectCorpus(registry, scopes)
        val nodes = ConstellationNodes(home, ConfixBlackboard(), scope = this)
        var response = HtxResponse(200, ByteSeries("<html>Not a PDF</html>"), htxHeaders("Content-Type" j "text/html"))
        var calls = 0
        var redirect = false
        val htx = HtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                calls++
                val reply = if (redirect) {
                    redirect = false
                    HtxResponse(302, headers = htxHeaders("Location" j "https://publisher.example/final.pdf"))
                } else response
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request,
                    response = reply), htxFrames())
            }
        })
        val admitted = mutableListOf<String>()
        scopes.onDocument = { project, id, _ -> admitted.add("$project/$id") }
        val links = LinkGrab(nodes, corpus, scopes, coroutineContext + htx)
        try {
            assertFailsWith<IllegalArgumentException> { links.root("https://publisher.example/report", null, requirePdf = true) }
            assertTrue(scopes.list().isEmpty())
            assertTrue(admitted.isEmpty())
            response = HtxResponse(200, ByteSeries("<html>Disguised PDF</html>"), htxHeaders("Content-Type" j "application/pdf"))
            assertFailsWith<IllegalArgumentException> { links.root("https://publisher.example/report", null, requirePdf = true) }
            assertTrue(scopes.list().isEmpty())
            assertTrue(admitted.isEmpty())
            val bytes = "%PDF-1.7\nOriginal public report\n%%EOF".encodeToByteArray()
            response = HtxResponse(200, ByteSeries(bytes), htxHeaders("Content-Type" j "application/pdf; charset=binary"))
            redirect = true
            val (project, id) = links.root("https://publisher.example/report", null, requirePdf = true)
            assertEquals(1, admitted.size)
            assertContentEquals(bytes, corpus.bytes(project, id))
            val notes = corpus.read(project, id + ".notes.md")!!.text
            assertTrue("url: https://publisher.example/final.pdf" in notes)
            assertTrue("requested: https://publisher.example/report" in notes)
            assertTrue("sha256: ${ContentId.of(bytes).hex}" in notes)
            assertEquals(4, calls)
        } finally {
            htx.close(); scopes.drain(); nodes.drain(); home.deleteRecursively()
        }
    }
}
