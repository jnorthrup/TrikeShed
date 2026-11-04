package borg.trikeshed.forge.server

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchIngress
import borg.trikeshed.couch.CouchStore
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.CouchWireRouter
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.persistence.CouchCommitStore
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.FileCasStore
import java.nio.file.Files
import kotlin.test.*

class ProjectDbBindingTest {
    private fun mismatchedCommitOwner(volatile: Boolean) {
        val home = Files.createTempDirectory("project-cas-owner").toFile()
        val files = JvmFileOperations()
        val storeCas = if (volatile) CasStore.inMemory() else FileCasStore(files, home.resolve("store-cas").absolutePath)
        val commitCas = FileCasStore(files, home.resolve("commit-cas").absolutePath)
        val commits = CouchCommitStore(commitCas, StorageDurability.DURABLE, files, home.resolve("commits").absolutePath)
        try {
            assertFailsWith<IllegalArgumentException> { CouchStoreFactory.durableCasBacked(storeCas, commits) }
        } finally { commits.close(); home.deleteRecursively() }
    }

    @Test fun durableCommitOwnerMustUseTheStoresCas() = mismatchedCommitOwner(false)
    @Test fun durableCommitOwnerRejectsAVolatileStoresCas() = mismatchedCommitOwner(true)

    private fun assertStoreRejected(store: CouchStore, cas: CasStore) {
        val db = Couch("project", store, cas)
        val registry = ProjectDbRegistry("primary")
        assertFailsWith<IllegalArgumentException> {
            registry.register(ProjectDb("project", "@upload", "assets", db, store,
                CouchAttachmentGateway(store, cas), CouchWireRouter(db, attachmentPrefix = "")))
        }
        assertNull(registry.get("project"))
    }

    @Test fun storeBackedByAnotherCasCannotEnterRegistry() {
        val selected = CasStore.inMemory()
        val other = CasStore.inMemory()
        assertStoreRejected(CouchStoreFactory.casBacked(other), selected)
    }

    @Test fun unverifiedStoreCannotEnterRegistry() {
        val cas = CasStore.inMemory()
        val backing = CouchStoreFactory.inMemory()
        assertStoreRejected(backing, cas)
        val ingress = object : CouchIngress {
            override fun putIntent(doc: Document, expectedRev: String?): Boolean = error("unexpected write")
            override fun deleteIntent(docId: String, expectedRev: String?): Boolean = error("unexpected delete")
        }
        assertStoreRejected(CouchStore(ingress, backing.head, backing.changes), cas)
    }

    @Test fun consistentlyBoundProjectCanEnterTheRegistry() {
        val cas = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        val db = Couch("project", store, cas)
        val project = ProjectDb("project", "@upload", "assets", db, store,
            CouchAttachmentGateway(store, cas), CouchWireRouter(db, attachmentPrefix = ""))
        val registry = ProjectDbRegistry("primary")
        registry.register(project)
        assertSame(project, registry.get("project"))
    }

    @Test fun mismatchedProjectSurfacesCannotEnterTheRegistry() {
        for (mismatch in _l["name", "gateway-store", "couch-store", "router", "cas"]) {
            val cas = CasStore.inMemory()
            val otherCas = CasStore.inMemory()
            val store = CouchStoreFactory.casBacked(cas)
            val otherStore = CouchStoreFactory.casBacked(cas)
            val db = Couch(if (mismatch == "name") "other" else "project",
                if (mismatch == "couch-store") otherStore else store, cas)
            val routed = if (mismatch == "router") Couch("project", otherStore, cas) else db
            val gateway = CouchAttachmentGateway(if (mismatch == "gateway-store") otherStore else store,
                if (mismatch == "cas") otherCas else cas)
            val registry = ProjectDbRegistry("primary")
            assertFailsWith<IllegalArgumentException>(mismatch) {
                registry.register(ProjectDb("project", "@upload", "assets", db, store, gateway,
                    CouchWireRouter(routed, attachmentPrefix = "")))
            }
            assertNull(registry.get("project"), mismatch)
        }
    }
}
