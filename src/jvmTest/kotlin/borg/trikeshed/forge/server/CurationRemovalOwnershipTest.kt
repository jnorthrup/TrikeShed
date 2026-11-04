package borg.trikeshed.forge.server

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
import borg.trikeshed.memory.*
import borg.trikeshed.module.*
import borg.trikeshed.narsese.ConstellationNodes
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class CurationRemovalOwnershipTest {
    @TempDir lateinit var directory: Path

    @Test fun removingAnUploadDoesNotDestroyAnUnownedDirectMembershipOrSharedBook(): Unit = runBlocking {
        val cas = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(store, cas)
        val registry = ProjectDbRegistry("primary")
        val scopes = ProjectScopes(JvmFileOperations(), gateway,
            CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, store))), cas, null,
            projectDbs = registry, ledgerFile = directory.resolve("projects.tsv").toFile(),
            filesRoot = directory.resolve("files").toFile())
        val project = scopes.beginUpload("source", manualIntake = true).name
        val corpus = JvmProjectCorpus(registry, scopes)
        val board = ConfixBlackboard.empty()
        val ctx = ModuleContext(Couch("ownership", store, cas), ReteNetwork(), ReteProductionRegistry(), null, null,
            board, cas, gateway, ModuleRouteRegistry(), this, { 0L }, directory.toFile())
        val nodes = ConstellationNodes(directory.toFile(), board, scope = this)
        val intake = CurationIntake.install(nodes, ctx, corpus, scopes, this)
        val book = "agreement.txt"
        val sourceMembers = nodes.root.resolve("$project.members")
        val otherMembers = nodes.root.resolve("another.members")
        val saved = nodes.root.resolve("books/$book.json")
        val reading = nodes.root.resolve("books/$book.readings/0.json")
        try {
            scopes.uploadPut(project, book, "source".encodeToByteArray(), manualIntake = true)
            sourceMembers.writeText("$book\n")
            otherMembers.writeText("$book\n")
            saved.writeText("retained direct book")
            reading.parentFile.mkdirs(); reading.writeText("retained direct reading")
            scopes.uploadRemove(project, book)
            assertEquals(_l[book], nodes.members(project), "upload removal cannot prove a direct membership belongs to it")
            assertEquals(_l[book], nodes.members("another"))
            assertEquals("retained direct book", saved.readText())
            assertEquals("retained direct reading", reading.readText())
            val status = intake.status().getValue(project) as Map<*, *>
            assertContains(status["note"] as String, "membership may remain")
        } finally {
            intake.drain(); nodes.drain(); registry.drain()
        }
    }
}
