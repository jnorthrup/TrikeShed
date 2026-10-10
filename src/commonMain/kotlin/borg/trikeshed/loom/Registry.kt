package borg.trikeshed.loom

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.*
import borg.trikeshed.userspace.nio.channels.*
import borg.trikeshed.userspace.nio.file.*
import borg.trikeshed.util.*

/**
 * loom-mesh `registry`: the read-only OCI registry role over public object-storage origins
 * (DEC-083). Ported: the local layout check run before an upload. `Registry::load` and `serve`
 * wait on the HTTPS client and the HTTP listener.
 */

/** `registry::parse_digest`: `sha256:` and 64 lowercase hex digits, the shape of a [ContentId]. */
fun parseDigest(value: String): ContentId? = runCatching { ContentId(value) }.getOrNull()

/** `registry::descriptor`: an OCI descriptor's digest and size. */
fun descriptor(item: Any?): Join<String, ULong> {
    val digest = (item as? Map<*, *>)?.get("digest") as? String
    if (digest == null || parseDigest(digest) == null) error("registry descriptor digest")
    val size = asU64(item["size"])?.takeIf { it <= Long.MAX_VALUE.toULong() } ?: error("registry descriptor size")
    return digest j size
}

/** `registry::descriptors`: the descriptors of [field]; none when absent. */
fun descriptors(json: Any, field: String): List<Join<String, ULong>> =
    when (val items = (json as? Map<*, *>)?.get(field)) {
        null -> emptyList()
        is List<*> -> if (items.size <= 1024) items.map(::descriptor) else error("registry manifest descriptors")
        else -> error("registry manifest descriptors")
    }

/** Rust `Path::join` for a relative [child]. */
fun join(root: String, child: String): String = if (root.isEmpty() || root.endsWith('/')) root + child else "$root/$child"

/**
 * At most [limit] bytes of [path] through the userspace NIO facade (`File::open`, then
 * `take(limit).read_to_end`); [unopened] and [unread] are the failures of the two steps.
 */
fun readFile(path: String, limit: Int, unopened: String, unread: String): ByteArray {
    val channel = try {
        FileChannel.open(path, setOf(StandardOpenOption.READ))
    } catch (failure: Exception) {
        error(unopened)
    }
    try {
        var bytes = ByteArray(minOf(limit, 1 shl 16))
        var size = 0
        while (size < limit) {
            if (size == bytes.size) bytes = bytes.copyOf(minOf(limit.toLong(), size * 2L).toInt())
            val count = try {
                channel.read(ByteBuffer.wrap(bytes, size, bytes.size - size))
            } catch (failure: Exception) {
                error(unread)
            }
            if (count < 0) break
            size += count
        }
        return bytes.copyOf(size)
    } finally {
        channel.close()
    }
}

/**
 * `registry::verify_layout`: every blob reachable from `index.json` of the OCI image layout at
 * [root] holds its stated size and hashes to its name. The top-level manifest digests come back
 * with their `org.opencontainers.image.ref.name` tags, in index order.
 */
fun verifyLayout(root: String): List<Join<String, String>> {
    fun blob(digest: String): String =
        join(join(root, "blobs/sha256"), parseDigest(digest)?.hex ?: error("layout digest"))
    fun readJson(path: String): Any =
        json(readFile(path, Int.MAX_VALUE, "layout read", "layout read"), unique = false) ?: error("layout json")
    fun check(digest: String, size: ULong) {
        val channel = try {
            FileChannel.open(blob(digest), setOf(StandardOpenOption.READ))
        } catch (failure: Exception) {
            error("layout blob missing")
        }
        val hasher = Sha256()
        var total = 0uL
        try {
            val buffer = ByteBuffer.allocate(1 shl 20)
            while (true) {
                buffer.clear()
                val count = try {
                    channel.read(buffer)
                } catch (failure: Exception) {
                    error("layout read")
                }
                if (count < 0) break
                hasher.update(buffer.array(), 0, count)
                total += count.toULong()
            }
        } finally {
            channel.close()
        }
        if (total != size || "sha256:" + hasher.digest().toLowerHex() != digest) error("layout blob mismatch")
    }

    val index = readJson(join(root, "index.json"))
    val tops = ArrayList<Join<String, String>>()
    val queue = ArrayList<Join<Join<String, ULong>, Boolean>>()
    for (item in (index as? Map<*, *>)?.get("manifests") as? List<*> ?: error("layout index")) {
        val top = descriptor(item)
        val annotations = (item as Map<*, *>)["annotations"] as? Map<*, *>
        tops.add(top.a j (annotations?.get("org.opencontainers.image.ref.name") as? String ?: ""))
        queue.add(top j true)
    }
    val seen = HashSet<String>()
    while (queue.isNotEmpty()) {
        val (next, manifest) = queue.removeAt(queue.size - 1)
        val (digest, size) = next
        if (!seen.add(digest)) continue
        check(digest, size)
        if (manifest) {
            val json = readJson(blob(digest))
            for (child in descriptors(json, "manifests")) queue.add(child j true)
            for (child in descriptors(json, "layers")) queue.add(child j false)
            (json as? Map<*, *>)?.get("config")?.let { queue.add(descriptor(it) j false) }
        }
    }
    return tops
}
