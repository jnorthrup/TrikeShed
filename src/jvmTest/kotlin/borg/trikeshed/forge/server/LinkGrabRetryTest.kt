<<<<<<< HEAD
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
    private class Rig(val scope: CoroutineScope, val route: suspend (HtxRequest) -> HtxResponse) {
        val home = Files.createTempDirectory("link-retry").toFile()
        val cas = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(store, cas)
        val registry = ProjectDbRegistry("primary")
        val scopes = ProjectScopes(JvmFileOperations(), gateway,
            CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, store))), cas, null,
            projectDbs = registry, ledgerFile = File(home, "projects.tsv"), filesRoot = File(home, "files"))
        val corpus = JvmProjectCorpus(registry, scopes, ProjectMiner(registry, scopes, cas, null, File(home, "files")))
        lateinit var htx: HtxElement
        val nodes = ConstellationNodes(home, ConfixBlackboard.empty(), scope = scope, cas = cas)
        suspend fun open(vararg urls: String): String {
            htx = openHtxElement(routeService = object : HtxRouteService {
                override suspend fun exchange(state: HtxExchangeState, request: HtxRequest) =
                    HtxExchangeResult(state.copy(response = route(request)))
            })
            val project = scopes.beginUpload("project").name
            val table = File(home, "constellations/books/root.html.jev").apply { mkdirs() }
            File(table, "0.json").writeText("""{"links":[${urls.joinToString(",") { """{"href":"$it","referenced":true,"noul":1}""" }}]}""")
            return project
        }
        suspend fun close() {
            nodes.drain(); htx.close(); scopes.unmount("project"); scopes.drain(); home.deleteRecursively()
        }
    }

    private fun response(body: String, status: Int = 200) = HtxResponse(status,
        ByteSeries(body.encodeToByteArray()), htxHeaders("Content-Type" j "text/html"))

    @Test fun orphanNotesDoNotSuppressRetry(): Unit = runBlocking {
        val rig = Rig(this) { response("<html>child</html>") }
        val project = rig.open("http://localhost/child")
        try {
            val digest = ContentId.of("<html>child</html>".encodeToByteArray()).hex
            rig.scopes.uploadPut(project, "localhost/child.html.notes.md",
                "url: http://localhost/child\nsha256: $digest\n".encodeToByteArray())
            LinkGrab(rig.nodes, rig.corpus, rig.scopes, rig.htx).grab(project, "root.html", DecisionProvider.JEV)
            assertNotNull(rig.corpus.document(project, "localhost/child.html"))
        } finally { rig.close() }
    }

    @Test fun failedAdmissionCanBeRetriedAfterBodyLands(): Unit = runBlocking {
        val rig = Rig(this) { response("<html>child</html>") }
        val project = rig.open("http://localhost/child")
        val offers = AtomicInteger()
        val links = LinkGrab(rig.nodes, rig.corpus, rig.scopes, rig.htx) { _, _, _ ->
            if (offers.incrementAndGet() == 1) error("admission refused")
        }
        try {
            links.grab(project, "root.html", DecisionProvider.JEV)
            assertNotNull(rig.corpus.document(project, "localhost/child.html"))
            links.grab(project, "root.html", DecisionProvider.JEV)
            assertEquals(2, offers.get(), "a retained but unadmitted body must be offered again")
        } finally { rig.close() }
    }

    @Test fun concurrentWaiterRetriesAClosedFailedFetch(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val rig = Rig(this) {
            val attempt = attempts.incrementAndGet()
            if (attempt == 1) { entered.complete(Unit); release.await() }
            response("<html>child</html>", if (attempt <= 2) 503 else 200)
        }
        val project = rig.open("http://localhost/child")
        val links = LinkGrab(rig.nodes, rig.corpus, rig.scopes, rig.htx)
        try {
            val first = async(Dispatchers.Default) { links.grab(project, "root.html", DecisionProvider.JEV) }
            withTimeout(5_000) { entered.await() }
            val second = async(Dispatchers.Default) { links.grab(project, "root.html", DecisionProvider.JEV) }
            release.complete(Unit)
            withTimeout(10_000) { first.await(); second.await() }
            assertNotNull(rig.corpus.document(project, "localhost/child.html"))
            assertEquals(3, attempts.get())
        } finally { release.complete(Unit); rig.close() }
    }

    @Test fun cancellationReleasesRequestedWorkForRetry(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val rig = Rig(this) {
            if (attempts.incrementAndGet() == 1) { entered.complete(Unit); release.await() }
            response("<html>child</html>")
        }
        val project = rig.open("http://localhost/child")
        val links = LinkGrab(rig.nodes, rig.corpus, rig.scopes, rig.htx)
        try {
            val first = async(Dispatchers.Default) { links.grab(project, "root.html", DecisionProvider.JEV) }
            withTimeout(5_000) { entered.await() }
            first.cancelAndJoin()
            links.grab(project, "root.html", DecisionProvider.JEV)
            assertNotNull(rig.corpus.document(project, "localhost/child.html"))
            assertEquals(2, attempts.get())
        } finally { release.complete(Unit); rig.close() }
    }

    @Test fun collidingStemsDoNotSuppressDifferentWorks(): Unit = runBlocking {
        val rig = Rig(this) { request -> response("<html>${request.target.requestPath}</html>") }
        val project = rig.open("http://localhost/a/b", "http://localhost/a_b")
        try {
            LinkGrab(rig.nodes, rig.corpus, rig.scopes, rig.htx).grab(project, "root.html", DecisionProvider.JEV)
            val documents = rig.corpus.docs(project, limit = 100)
            assertEquals(2, documents.size, "different works must not collapse onto one sanitized stem")
            assertEquals(2, documents.map { it.cid }.distinct().size)
        } finally { rig.close() }
    }

    @Test fun concurrentUserUploadIsNotOverwritten(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rig = Rig(this) { entered.complete(Unit); release.await(); response("<html>remote</html>") }
        val project = rig.open("http://localhost/child")
        try {
            val traversal = async(Dispatchers.Default) {
                LinkGrab(rig.nodes, rig.corpus, rig.scopes, rig.htx).grab(project, "root.html", DecisionProvider.JEV)
            }
            withTimeout(5_000) { entered.await() }
            val owned = "<html>user document</html>".encodeToByteArray()
            val notes = "user-owned notes".encodeToByteArray()
            rig.scopes.uploadPut(project, "localhost/child.html", owned)
            rig.scopes.uploadPut(project, "localhost/child.html.notes.md", notes)
            release.complete(Unit)
            withTimeout(10_000) { traversal.await() }
            assertContentEquals(owned, rig.corpus.bytes(project, "localhost/child.html"))
            assertContentEquals(notes, rig.corpus.bytes(project, "localhost/child.html.notes.md"))
        } finally { release.complete(Unit); rig.close() }
    }

    @Test fun delayedFetchCannotWriteIntoRemountedProject(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rig = Rig(this) { entered.complete(Unit); release.await(); response("<html>remote</html>") }
        val project = rig.open("http://localhost/child")
        try {
            val traversal = async(Dispatchers.Default) {
                LinkGrab(rig.nodes, rig.corpus, rig.scopes, rig.htx).grab(project, "root.html", DecisionProvider.JEV)
            }
            withTimeout(5_000) { entered.await() }
            assertTrue(rig.scopes.unmount(project))
            rig.scopes.beginUpload(project)
            release.complete(Unit)
            withTimeout(10_000) { traversal.await() }
            assertNull(rig.corpus.document(project, "localhost/child.html"))
        } finally { release.complete(Unit); rig.close() }
    }

    @Test fun existingSymlinkCannotRedirectTheLanding(): Unit = runBlocking {
        val rig = Rig(this) { response("<html>remote</html>") }
        val project = rig.open("http://localhost/child")
        try {
            val outside = File(rig.home, "outside").apply { mkdirs() }
            val original = "<html>user document</html>".encodeToByteArray()
            File(outside, "child.html").writeBytes(original)
            val projectRoot = File(rig.home, "files/$project").apply { mkdirs() }
            Files.createSymbolicLink(File(projectRoot, "localhost").toPath(), outside.toPath())
            LinkGrab(rig.nodes, rig.corpus, rig.scopes, rig.htx).grab(project, "root.html", DecisionProvider.JEV)
            assertContentEquals(original, File(outside, "child.html").readBytes())
        } finally { rig.close() }
    }

    @Test fun reentrantSubscriberCannotMakeLandingOverwriteUserDocument(): Unit = runBlocking {
        val rig = Rig(this) { response("<html>remote</html>") }
        val project = rig.open("http://localhost/child")
        val owned = "<html>user document</html>".encodeToByteArray()
        val store = requireNotNull(rig.registry.get(project)).store
        val unsubscribe = store.subscribeMutations { event ->
            if (event is CouchStore.MutationEvent.Inserted && event.doc.id == "localhost/child.html.notes.md")
                rig.scopes.uploadPut(project, "localhost/child.html", owned)
        }
        try {
            LinkGrab(rig.nodes, rig.corpus, rig.scopes, rig.htx).grab(project, "root.html", DecisionProvider.JEV)
            assertContentEquals(owned, rig.corpus.bytes(project, "localhost/child.html"))
        } finally { unsubscribe(); rig.close() }
    }
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
||||||| 678c7a818
=======
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
>>>>>>> d771ffd01da34e146b9e947cdae575db0d64781e
