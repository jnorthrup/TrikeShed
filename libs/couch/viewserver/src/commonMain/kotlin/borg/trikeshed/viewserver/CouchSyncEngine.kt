package borg.trikeshed.viewserver

import borg.trikeshed.htx.client.HtxElement
import borg.trikeshed.htx.client.HtxTransport
import borg.trikeshed.htx.client.createHttpsHandler
import borg.trikeshed.miniduck.MiniDuckBlockCodec
import borg.trikeshed.miniduck.tablespace.BlockStore
import borg.trikeshed.miniduck.tablespace.NioBlockWal
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

class CouchSyncEngine(
    private val fileOps: FileOperations,
    private val blockStore: BlockStore,
    private val wal: NioBlockWal,
    private val couchUrl: String = "http://localhost:5984",
    private val database: String = "trike_git",
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<CouchSyncEngine>
    override val key: CoroutineContext.Key<*> get() = Key

    private val indexer = GitTreeIndexer(fileOps, "/")

    suspend fun fullSync(htx: HtxElement) {
        htx.registerTransport(HtxTransport.HTTPS, createHttpsHandler())
        val docs = indexer.indexHead()
        for (doc in docs) upsertDocument(htx, doc)
    }

    fun startContinuous(scope: CoroutineScope, htx: HtxElement, rootDir: String): Job {
        htx.registerTransport(HtxTransport.HTTPS, createHttpsHandler())
        val watcher = NioFileWatcher(fileOps, root = rootDir)
        return watcher.start(scope) { change ->
            when (change) {
                is NioFileWatcher.FileChange.Created -> upsertDocument(htx, fileToDoc(change.path, change.content))
                is NioFileWatcher.FileChange.Modified -> upsertDocument(htx, fileToDoc(change.path, change.content))
                is NioFileWatcher.FileChange.Deleted -> deleteDocument(htx, change.path)
            }
        }
    }

    private suspend fun upsertDocument(htx: HtxElement, doc: GitTreeIndexer.GitDoc) {
        val response = htx.request("PUT", "$couchUrl/${doc.database}/${doc.id}", body = doc.body, transport = HtxTransport.HTTPS)
        if (response.status in 200..299) {
            val block = MiniDuckBlockCodec.decode(doc.body)
            blockStore.putWithId(doc.database, doc.id, block)
            wal.appendPut(doc.database, doc.id, block)
        }
    }

    private suspend fun deleteDocument(htx: HtxElement, path: String) {
        val docId = "path/$path"
        val response = htx.request("DELETE", "$couchUrl/$database/$docId", transport = HtxTransport.HTTPS)
        if (response.status in 200..299) {
            blockStore.remove(database, docId)
            wal.appendRemove(database, docId)
        }
    }

    private fun fileToDoc(path: String, content: String): GitTreeIndexer.GitDoc {
        val hash = content.hashCode().toString(16).padStart(8, '0')
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        return GitTreeIndexer.GitDoc(
            database = database,
            id = "path/$path",
            body = """{"_id":"path/$path","path":"$path","sha1":"$hash","mode":"100644","content":"$escaped"}""",
        )
    }

    suspend fun registerViews(htx: HtxElement) {
        htx.registerTransport(HtxTransport.HTTPS, createHttpsHandler())
        val ddoc = """{"_id":"_design/git_tree","language":"javascript","views":{"by_path":{"map":"function(doc){if(doc.path) emit(doc.path, null)}"},"by_sha1":{"map":"function(doc){if(doc.sha1) emit(doc.sha1, null)}"}}}"""
        htx.request("PUT", "$couchUrl/$database/_design/git_tree", body = ddoc, transport = HtxTransport.HTTPS)
    }

    /** Push the kline cascade design document to a CouchDB instance. */
    suspend fun registerKlineViews(htx: HtxElement, klineDb: String = "klines") {
        htx.registerTransport(HtxTransport.HTTPS, createHttpsHandler())
        htx.request(
            "PUT",
            "$couchUrl/$klineDb/${KlineDesignDoc.DESIGN_ID}",
            body = KlineDesignDoc.toJson(),
            transport = HtxTransport.HTTPS,
        )
    }
}
