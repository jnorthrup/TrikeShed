package borg.literbike.ccek.store.couchdb

/**
 * CouchDB module for LiterBike
 *
 * This module provides a CouchDB 1.7.2 compatible API with extensions for:
 * - IPFS distributed storage
 * - M2M (machine-to-machine) communication
 * - Tensor operations
 * - Cursor-based pagination
 * - Git synchronization
 *
 * Ported from the Rust implementation in ccek/store/src/couchdb/
 */

// Public re-exports (matching the Rust mod.rs pattern)

// Core types
public typealias DocId = String
public typealias RevId = String
public typealias AttachmentDigest = String
public typealias CouchResult<T> = Result<T>

// Error handling
public fun <T> Result<T>.toCouchResult(errorMessage: String = "Operation failed"): CouchResult<T> {
    return mapCatching { it }.recoverCatching {
        throw CouchError.internalServerError(it.message ?: errorMessage)
    }
}

// Convenience factory functions
public object CouchDb {

    /**
     * Create a new database manager
     */
    public fun createDatabaseManager(dataDir: String): DatabaseManager {
        return DatabaseManager.new(dataDir)
    }

    /**
     * Create a new view server
     */
    public fun createViewServer(config: ViewServerConfig = ViewServerConfig.default()): ViewServer {
        return ViewServer.new(config).getOrThrow()
    }

    /**
     * Create a new M2M manager
     */
    public fun createM2mManager(nodeId: String? = null, config: M2mConfig = M2mConfig.default()): M2mManager {
        return M2mManager.new(nodeId, config)
    }

    /**
     * Create a new tensor engine
     */
    public fun createTensorEngine(): TensorEngine {
        return TensorEngine.new()
    }

    /**
     * Create a new IPFS manager
     */
    public fun createIpfsManager(config: IpfsConfig = IpfsConfig.default()): IpfsManager {
        return IpfsManager.new(config).getOrThrow()
    }

    /**
     * Create a new IPFS-backed KV store
     */
    public fun createKvStore(
        ipfsManager: IpfsManager,
        config: KvStoreConfig = KvStoreConfig.default()
    ): IpfsKvStore {
        return IpfsKvStore.new(ipfsManager, config)
    }

    /**
     * Create a new git sync manager
     */
    public suspend fun createGitSyncManager(
        config: GitSyncConfig,
        database: DatabaseInstance
    ): CouchResult<GitSyncManager> {
        return GitSyncManager.new(config, database)
    }

    /**
     * Create application state with all components
     */
    public suspend fun createAppState(
        dataDir: String = ".",
        defaultDb: String = "main"
    ): CouchResult<AppState> {
        val dbManager = createDatabaseManager(dataDir)
        val viewServer = createViewServer()
        val m2mManager = createM2mManager()
        val tensorEngine = createTensorEngine()
        val ipfsManager = createIpfsManager()
        val kvStore = createKvStore(ipfsManager)

        dbManager.initializeDefaults()

        return Result.success(
            AppState(
                dbManager = dbManager,
                viewServer = viewServer,
                m2mManager = m2mManager,
                tensorEngine = tensorEngine,
                ipfsManager = ipfsManager,
                kvStore = kvStore,
                rfDefaultDb = defaultDb
            )
        )
    }
}
