package borg.trikeshed.spokes

import borg.trikeshed.ipfs.BlockStore
import borg.trikeshed.ipfs.CID
import borg.trikeshed.ipfs.DhtService
import borg.trikeshed.torrent.KademliaDht
import borg.trikeshed.torrent.TorrentTracker
import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import kotlinx.coroutines.Job

/**
 * SpokesElement — the polyplatform CDN hub that recycles our DHT, P2P, and transport libs.
 *
 * Architecture:
 *   - Reflect: mirror .m2 artifacts into ipfs BlockStore, announce to KademliaDht
 *   - Project: build artifacts on-the-fly from git coords, serve as maven/npm
 *   - Pass-through: cache & mirror external registries through VPS (opt-in)
 *
 * Self-sufficient first — jitpack/npm/maven are opt-in pass-through layers,
 * not dependencies. Our DHT + KademliaDht + ArtifactRegistry form the core.
 */
class SpokesElement(
    val blockStore: BlockStore,
    val dhtService: DhtService,
    val registry: ArtifactRegistry,
    parentJob: Job? = null,
) : AsyncContextElement(parentJob = parentJob) {

    companion object Key : AsyncContextKey<SpokesElement>() {
        @Suppress("FunctionName")
        fun Standalone(
            m2CacheDir: String,
            parentJob: Job? = null,
        ): SpokesElement {
            val bs = FileSystemBlockStore(m2CacheDir)
            val dht = DhtService()
            val reg = InMemoryArtifactRegistry()
            return SpokesElement(bs, dht, reg, parentJob)
        }
    }

    override val key: AsyncContextKey<SpokesElement> get() = Key

    /** Resolve a coordinate from our self-sufficient mesh. */
    suspend fun resolve(coord: Coordinate): ArtifactSnapshot? {
        val cid = CID(coord.mavenCoord.encodeToByteArray())
        blockStore.get(cid)?.let { data ->
            return ArtifactSnapshot(coord, data, Source.LOCAL_CACHE)
        }
        val providers = dhtService.findProviders(cid)
        if (providers.isNotEmpty()) {
            // DHT has routes — future: fetch from peer via SCTP/Torrent transport
        }
        return null
    }

    /** Publish locally, announce to DHT, index in registry. */
    suspend fun publish(coord: Coordinate, payload: ByteArray) {
        val cid = CID(coord.mavenCoord.encodeToByteArray())
        blockStore.put(cid, payload)
        dhtService.announceProvider(cid, registry.localNodeAddress)
        registry.register(coord, Source.LOCAL_CACHE)
    }

    /** Search index. */
    fun search(query: CoordQuery): List<Coordinate> = registry.search(query)

    /** Queue on-the-fly git build. */
    suspend fun buildFromGit(
        repoUrl: String,
        commitSha: String,
    ): GitBuildJob = registry.queueGitBuild(repoUrl, commitSha)

    data class ArtifactSnapshot(
        val coord: Coordinate,
        val data: ByteArray,
        val source: Source,
    )

    data class GitBuildJob(
        val id: String,
        val repoUrl: String,
        val commitSha: String,
        val status: BuildStatus,
        val builtCoord: Coordinate? = null,
    )

    enum class BuildStatus { QUEUED, CLONING, COMPILING, PUBLISHED, FAILED }
}

/**
 * ArtifactRegistry — searchable index turning the CDN into a hub.
 */
interface ArtifactRegistry {
    val localNodeAddress: String
    fun register(coord: Coordinate, source: Source)
    fun search(query: CoordQuery): List<Coordinate>
    operator fun get(coord: Coordinate): Source?
    fun queueGitBuild(repoUrl: String, commitSha: String): SpokesElement.GitBuildJob
    fun all(): List<Coordinate>
}

data class CoordQuery(
    val group: String? = null,
    val artifact: String? = null,
    val version: String? = null,
    val packaging: Packaging? = null,
    val source: Source? = null,
    val maxResults: Int = 50,
)

enum class BuildSystem {
    GRADLE,
    CARGO,
    OPAM,
    ;

    /** CLI command to build a project of this type from its root directory. */
    fun buildCommand(): String = when (this) {
        GRADLE -> "./gradlew assemble --no-daemon"
        CARGO -> "cargo package --no-verify"
        OPAM -> "dune build @install && opam pin add . --with-doc --with-test -y"
    }

    /** Output directory relative to project root where the artifact appears after build. */
    fun outputDir(): String = when (this) {
        GRADLE -> "build/libs/"
        CARGO -> "target/package/"
        OPAM -> "_build/default/"
    }

    /** File extension of the built artifact. */
    fun artifactExt(): String = when (this) {
        GRADLE -> "jar"
        CARGO -> "crate"
        OPAM -> "opam"
    }
}

enum class Source {
    LOCAL_CACHE,
    GIT_BUILD,
    PASSTHROUGH,
    CARGO_REGISTRY,
    OPAM_REGISTRY,
}

class InMemoryArtifactRegistry: ArtifactRegistry {
    override val localNodeAddress: String = "/ip4/127.0.0.1/tcp/4001"
    private val index = mutableMapOf<String, Source>()
    private val builds = mutableListOf<SpokesElement.GitBuildJob>()

    override fun register(coord: Coordinate, source: Source) {
        index[coord.mavenCoord] = source
    }
    override fun search(query: CoordQuery): List<Coordinate> {
        return index.entries.mapNotNull { (cs, src) ->
            if (query.source != null && src != query.source) return@mapNotNull null
            val (g, a, v, pStr) = cs.split(":")
            val p = try { Packaging.valueOf(pStr.uppercase()) } catch (_: IllegalArgumentException) { Packaging.JAR }
            val c = Coordinate(g, a, v, p)
            if (query.group != null && !c.group.startsWith(query.group)) return@mapNotNull null
            if (query.artifact != null && !c.artifact.contains(query.artifact, ignoreCase = true)) return@mapNotNull null
            if (query.version != null && c.version != query.version) return@mapNotNull null
            if (query.packaging != null && c.packaging != query.packaging) return@mapNotNull null
            c
        }.take(query.maxResults)
    }
    override operator fun get(coord: Coordinate): Source? = index[coord.mavenCoord]
    override fun queueGitBuild(repoUrl: String, commitSha: String): SpokesElement.GitBuildJob {
        val job = SpokesElement.GitBuildJob(
            id = "build-${builds.size}", repoUrl = repoUrl, commitSha = commitSha,
            status = SpokesElement.BuildStatus.QUEUED,
        )
        builds += job; return job
    }
    override fun all(): List<Coordinate> =
        index.keys.map { cs ->
            val (g, a, v, pStr) = cs.split(":")
            val p = try { Packaging.valueOf(pStr.uppercase()) } catch (_: IllegalArgumentException) { Packaging.JAR }
            Coordinate(g, a, v, p)
        }
}

/**
 * FileSystemBlockStore — disk-backed ipfs BlockStore.
 */
class FileSystemBlockStore(private val rootDir: String) : BlockStore {
    private fun pathFor(cid: CID): String {
        val hex = cid.bytes.joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return "$rootDir/blocks/${hex.substring(0, 2)}/${hex.substring(2)}"
    }
    override suspend fun put(cid: CID, data: ByteArray) { /* jvmMain impl */ }
    override suspend fun get(cid: CID): ByteArray? = null // jvmMain impl
}

/**
 * SpokesServer — the polyplatform CDN hub running on a private VPS.
 *
 * Recycles existing libs:
 *   - IpfsElement/BlockStore for content-addressed JAR storage
 *   - KademliaDht for P2P coordinate announcement
 *   - SctpElement for reliable multi-path transport of payloads
 *   - TorrentTracker for coordinating peer swarm builds
 *
 * Endpoints:
 *   GET /<group>/<artifact>/<version>/<file>   — Maven artifact
 *   GET /<package>/<version>                     — NPM passthrough (opt-in)
 *   POST /hook/ref                               — Trigger git build
 *   GET /index?group=...&artifact=...            — Search
 */
class SpokesServer(
    val spokes: SpokesElement,
) : AsyncContextElement() {

    companion object Key : AsyncContextKey<SpokesServer>()
    override val key: AsyncContextKey<SpokesServer> get() = Key
    val httpPort: Int = 8080

    suspend fun serveMaven(coord: Coordinate): ArtifactForServe? {
        val snap = spokes.resolve(coord)
        return snap?.let { ArtifactForServe(coord, it.data, coord.packaging.contentType) }
    }

    suspend fun onGitHook(repoUrl: String, commitSha: String): SpokesElement.GitBuildJob =
        spokes.buildFromGit(repoUrl, commitSha)

    fun searchIndex(query: CoordQuery): List<Coordinate> = spokes.search(query)

    data class ArtifactForServe(
        val coord: Coordinate,
        val data: ByteArray,
        val contentType: String,
    )
}
