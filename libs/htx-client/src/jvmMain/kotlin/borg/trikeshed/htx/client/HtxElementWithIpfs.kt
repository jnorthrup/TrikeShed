package borg.trikeshed.htx.client

import borg.trikeshed.htx.client.ipfs.CID
import borg.trikeshed.htx.client.ipfs.CarParseResult
import borg.trikeshed.htx.client.ipfs.CakManager
import borg.trikeshed.htx.client.ipfs.CakManagerFactory
import borg.trikeshed.htx.client.ipfs.MemoryBlockStore
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

/**
 * JVM-only extension of HtxElement that adds IPFS CAK operations.
 * The CAK manager, DHT service, and CAR integration are JVM-only
 * (depend on userspace NIO), so this subclass lives in jvmMain.
 */
class HtxElementWithIpfs(
    baseUrl: String = "http://127.0.0.1",
    private val blockStore: HtxElementWithIpfsBlockStore? = null,
    tlsConfig: borg.trikeshed.tls.TlsConfig? = null,
) : HtxElement(baseUrl, tlsConfig) {

    private var _cakManager: CakManager? = null

    /** IPFS CAK Manager — lazy init on first access. */
    val cak: CakManager
        get() {
            if (_cakManager == null) {
                _cakManager = CakManagerFactory.create(
                    blockStore = blockStore ?: MemoryBlockStore(),
                )
            }
            return _cakManager!!
        }

    override suspend fun open() {
        super.open()
        if (blockStore != null) {
            // Eagerly initialize CAK to validate block store
            cak
        }
    }

    override suspend fun close() {
        _cakManager?.close()
        super.close()
    }

    /** IPFS: store data and return its CID. */
    suspend fun ipfsPut(data: ByteArray): CID = cak.put(data)

    /** IPFS: retrieve data by CID. */
    suspend fun ipfsGet(cid: CID): ByteArray? = cak.get(cid)

    /** IPFS: pin a CID at the given address. */
    suspend fun ipfsPin(cid: CID, address: String = "local") = cak.pin(cid, address)

    /** IPFS: import a CAR archive, returning parsed roots and blocks. */
    suspend fun ipfsImportCar(data: ByteArray): CarParseResult = cak.importCar(data)

    /** IPFS: export blocks as a CAR archive. */
    suspend fun ipfsExportCar(roots: List<CID>, version: Int = 2): ByteArray = cak.exportCar(roots, version)
}

/**
 * JVM-only block store interface for HtxElementWithIpfs.
 * Allows callers to provide custom BlockStore implementations.
 */
interface HtxElementWithIpfsBlockStore : borg.trikeshed.htx.client.ipfs.BlockStore

object HtxElementWithIpfsFactory {
    suspend fun open(
        baseUrl: String = "http://127.0.0.1",
        blockStore: HtxElementWithIpfsBlockStore? = null,
        tlsConfig: borg.trikeshed.tls.TlsConfig? = null,
    ): HtxElementWithIpfs {
        val element = HtxElementWithIpfs(baseUrl, blockStore, tlsConfig)
        element.open()
        return element
    }
}
