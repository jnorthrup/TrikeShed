package borg.trikeshed.miniduck.cas

import borg.trikeshed.lib.*
import borg.trikeshed.miniduck.ObjectStoreAdapter
import borg.trikeshed.miniduck.ObjectStoreProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

@DslMarker
annotation class CASBuilder

/**
 * CAS DSL — inline reified configuration for layered bucket stores.
 *
 * Layering (bottom-up):
 *   1. Raw provider (S3/GCS/OSS/FS)          — ObjectStoreAdapter
 *   2. Btrfs (copy-on-write, snapshots)      — BtrfsAdapter
 *   3. ISAM (indexed sequential access)      — ISAMAdapter
 *  4. IPFS (CID translation, DHT routing)    — IPFSAdapter
 *  5. Git (commit-tree CAS decoder)          — GitCASAdapter
 *  6. Pijul (patch-based CAS gateway)        — PijulCASAdapter
 */
@CASBuilder
class CASLayerConfig(
    val bucket: String = "trikeshed-cas",
) {
    private val layers = mutableListOf<CASLayer>()

    /** Raw cloud/fs provider — the bottom layer. */
    inline fun <reified P : ObjectStoreProvider> provider(
        crossinline configure: P.() -> Unit = {},
    ): CASLayerConfig {
        val adapter = P.adapter(configure)
        layers += CASLayer.Provider(adapter)
        return this
    }

    /** Btrfs layer — copy-on-write, snapshots, compression. */
    inline fun btrfs(
        crossinline configure: BtrfsConfig.() -> Unit = {},
    ): CASLayerConfig {
        val cfg = BtrfsConfig().apply(configure)
        layers += CASLayer.Btrfs(cfg)
        return this
    }

    /** ISAM layer — indexed sequential access with B+tree. */
    inline fun isam(
        crossinline configure: ISAMConfig.() -> Unit = {},
    ): CASLayerConfig {
        val cfg = ISAMConfig().apply(configure)
        layers += CASLayer.ISAM(cfg)
        return this
    }

    /** IPFS layer — CID translation, UnixFS, DHT routing. */
    inline fun ipfs(
        crossinline configure: IPFSConfig.() -> Unit = {},
    ): CASLayerConfig {
        val cfg = IPFSConfig().apply(configure)
        layers += CASLayer.IPFS(cfg)
        return this
    }

    /** Git layer — commit tree CAS decoder. */
    inline fun git(
        crossinline configure: GitConfig.() -> Unit = {},
    ): CASLayerConfig {
        val cfg = GitConfig().apply(configure)
        layers += CASLayer.Git(cfg)
        return this
    }

    /** Pijul layer — patch-based CAS gateway. */
    inline fun pijul(
        crossinline configure: PijulConfig.() -> Unit = {},
    ): CASLayerConfig {
        val cfg = PijulConfig().apply(configure)
        layers += CASLayer.Pijul(cfg)
        return this
    }

    /** Build the layered CAS stack. */
    fun build(): CAS = {
        var inner: CASLayer = CASLayer.Terminal(bucket)
        for (layer in layers.reversed()) {
            inner = layer.wrap(inner)
        }
        CAS(inner, bucket)
    }
}

/** CAS configuration entry point. */
fun cas(block: CASLayerConfig.() -> Unit): CAS = CASLayerConfig().apply(block).build()

/** Layer types in the stack. */
sealed class CASLayer(
    val name: String,
) {
    data class Provider(val adapter: ObjectStoreAdapter) :
        CASLayer("provider.${adapter.provider.name}")

    data class Btrfs(val config: BtrfsConfig) :
        CASLayer("btrfs")

    data class ISAM(val config: ISAMConfig) :
        CASLayer("isam")

    data class IPFS(val config: IPFSConfig) :
        CASLayer("ipfs")

    data class Git(val config: GitConfig) :
        CASLayer("git")

    data class Pijul(val config: PijulConfig) :
        CASLayer("pijul")

    data class Terminal(val bucket: String) :
        CASLayer("terminal")

    /** Wrap inner layer with this layer's semantics. */
    fun wrap(inner: CASLayer): CASLayer = when (this) {
        is Provider -> this
        is Btrfs -> CASLayer.Btrfs(config) { inner }
        is ISAM -> CASLayer.ISAM(config) { inner }
        is IPFS -> CASLayer.IPFS(config) { inner }
        is Git -> CASLayer.Git(config) { inner }
        is Pijul -> CASLayer.Pijul(config) { inner }
        is Terminal -> this
    }
}

/** Layer-specific configurations. */
data class BtrfsConfig(
    val compression: Compression = Compression.ZSTD,
    val snapshotRetention: Int = 100,
    val cow: Boolean = true,
) {
    enum class Compression { NONE, ZSTD, LZ4 }
}

data class ISAMConfig(
    val pageSize: Int = 4096,
    val keyExtractor: (ByteArray) -> ByteArray = { it },
    val btreeOrder: Int = 128,
)

data class IPFSConfig(
    val gateway: String = "/ipfs/",
    val pinStrategy: PinStrategy = PinStrategy.RECURSIVE,
    val cidVersion: Int = 1,
) {
    enum class PinStrategy { RECURSIVE, DIRECT, NONE }
}

data class GitConfig(
    val repoPath: String = ".git",
    val objectStore: ObjectStore = ObjectStore.FILESYSTEM,
) {
    enum class ObjectStore { FILESYSTEM, MEMORY }
}

data class PijulConfig(
    val repoPath: String = ".pijul",
    val patchFormat: PatchFormat = PatchFormat.BINARY,
) {
    enum class PatchFormat { BINARY, TEXT }
}

/** Layer implementations that wrap inner layer. */
private fun CASLayer.Btrfs.wrap(inner: CASLayer): CASLayer = BtrfsWrapper(config, inner)
private fun CASLayer.ISAM.wrap(inner: CASLayer): CASLayer = ISAMWrapper(config, inner)
private fun CASLayer.IPFS.wrap(inner: CASLayer): CASLayer = IPFSWrapper(config, inner)
private fun CASLayer.Git.wrap(inner: CASLayer): CASLayer = GitWrapper(config, inner)
private fun CASLayer.Pijul.wrap(inner: CASLayer): CASLayer = PijulWrapper(config, inner)

/** Layer wrapper implementations. */
interface CASLayerWrapper : CASLayer {
    val inner: CASLayer
    override val name: String
        get() = "${this::class.simpleName}(${inner.name})"
}

data class BtrfsWrapper(val config: BtrfsConfig, val inner: CASLayer) : CASLayer("btrfs"), CASLayerWrapper
data class ISAMWrapper(val config: ISAMConfig, val inner: CASLayer) : CASLayer("isam"), CASLayerWrapper
data class IPFSWrapper(val config: IPFSConfig, val inner: CASLayer) : CASLayer("ipfs"), CASLayerWrapper
data class GitWrapper(val config: GitConfig, val inner: CASLayer) : CASLayer("git"), CASLayerWrapper
data class PijulWrapper(val config: PijulConfig, val inner: CASLayer) : CASLayer("pijul"), CASLayerWrapper

/** ObjectStoreProvider extension for adapter creation. */
fun ObjectStoreProvider.adapter(block: ObjectStoreProvider.() -> Unit): ObjectStoreAdapter =
    when (this) {
        ObjectStoreProvider.AWS_S3 -> S3Adapter()
        ObjectStoreProvider.GCS -> GCSAdapter()
        ObjectStoreProvider.ALIBABA_OSS -> OSSAdapter()
        ObjectStoreProvider.FILESYSTEM -> FileSystemAdapter()
        ObjectStoreProvider.MEMORY -> InMemoryAdapter()
    }.apply(block)

/** Adapter implementations (stubs — real ones in jvmMain). */
interface S3Adapter : ObjectStoreAdapter
interface GCSAdapter : ObjectStoreAdapter
interface OSSAdapter : ObjectStoreAdapter
interface FileSystemAdapter : ObjectStoreAdapter
interface InMemoryAdapter : ObjectStoreAdapter