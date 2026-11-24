package borg.trikeshed.agent.mirrormux

import borg.trikeshed.cli.htx.Aria2Options
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyMap
import borg.trikeshed.torrent.TorrentFile
import borg.trikeshed.torrent.percentEncode

/**
 * The design of hf-aria2-mcp, in commonMain: a catalog pins files, mirrors serve them, and trust comes from the
 * pin, never from the mirror. A [MirrorFile] is the same bytes under one [path] at every mirror base; [size] and
 * [checksum] (aria2's `sha-256=<hex>` or `sha-1=<hex>`) are the pin, and a file with no checksum is taken on
 * the mirror's word. [destination] is where it lands, relative to a download root.
 */
data class MirrorFile(val path: String, val destination: String, val size: Long? = null, val checksum: String? = null)

/** The whole bytes that every one of [uris] names, from whichever answers; the host that lends it decides how they are fetched. */
fun interface MirrorFetch {
    suspend operator fun invoke(uris: List<String>): ByteArray
}

/** Pinned files by id, and named groups of ids: one group is one request to fetch. */
class MirrorCatalog(val files: Map<String, MirrorFile>, val groups: Map<String, List<String>>) {
    fun group(name: String): List<MirrorFile> =
        (groups[name] ?: throw IllegalArgumentException("no group $name: ${groups.keys}")).map { files[it] ?: error("group $name names $it, which the catalog does not hold") }

    /** The bytes a group pins, counting a file of unknown size as nothing. */
    fun bytes(name: String): Long = group(name).sumOf { it.size ?: 0L }

    companion object {
        const val DOWNLOADS = "turbofit.downloads/v1"
        const val MANIFEST = "turbofit.artifact-manifest/v1"

        /** A Turbofit catalog: `turbofit.downloads/v1` (files and groups) or `turbofit.artifact-manifest/v1` (artifacts, whose families are the groups). */
        fun of(json: String): MirrorCatalog {
            val raw = reifyMap(json)
            return when (val schema = raw["schema"]) {
                DOWNLOADS -> MirrorCatalog(
                    (raw["files"] as Map<*, *>).entries.associate { (id, row) -> id as String to huggingFace(row as Map<*, *>) },
                    (raw["groups"] as Map<*, *>).entries.associate { (group, ids) -> group as String to (ids as List<*>).map { it as String } })
                MANIFEST -> {
                    val files = LinkedHashMap<String, MirrorFile>()
                    val groups = LinkedHashMap<String, MutableList<String>>()
                    for ((i, row) in (raw["artifacts"] as List<*>).withIndex()) {
                        row as Map<*, *>
                        val id = row["id"] as String? ?: "${row["repo_id"]}/${row["path"]}#$i"
                        files[id] = huggingFace(row)
                        for (family in row["families"] as List<*>? ?: emptyList<Any?>()) groups.getOrPut(family as String) { ArrayList() }.add(id)
                    }
                    MirrorCatalog(files, groups)
                }
                else -> throw IllegalArgumentException("unsupported catalog schema: $schema")
            }
        }

        /** A catalog row (`repo_id`, `revision`, `path`, and `destination`, `sha256`, `size_bytes` where given) as the file Hugging Face's layout serves at `/<repo>/resolve/<revision>/<path>`. */
        fun huggingFace(row: Map<*, *>): MirrorFile {
            val repo = row["repo_id"] as String; val path = row["path"] as String
            return MirrorFile("/$repo/resolve/${row["revision"] as String}/$path",
                (row["destination"] as String?)?.takeIf { it.isNotEmpty() } ?: "${repo.replace("/", "--")}/$path",
                (row["size_bytes"] as Number?)?.toLong(), (row["sha256"] as String?)?.takeIf { it.isNotEmpty() }?.let { "sha-256=$it" })
        }
    }
}

/**
 * The bases that serve one layout, at most [MAX] of them, each once, in the order given. [uris] is a file at each;
 * [options] is the download hf-aria2-mcp asks aria2 for: [MAX] ranges of at least [MIN_SPLIT], [MAX] connections
 * a mirror, kept when already present, and the pinned checksum.
 */
class Mirrors(bases: List<String>) {
    val bases: List<String> = bases.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct().take(MAX)

    fun uris(file: MirrorFile): List<String> = bases.map { it + file.path }

    fun options(file: MirrorFile, root: String): Aria2Options {
        val out = destination(root, file.destination)
        return Aria2Options(dir = out.substringBeforeLast('/'), out = out.substringAfterLast('/'), split = MAX, maxConnectionPerServer = MAX,
            minSplitSize = MIN_SPLIT, continueDownload = true, checksum = file.checksum, uris = uris(file), length = file.size)
    }

    companion object {
        /** Ranges a file and mirrors a file: aria2's own cap on connections to one server. */
        const val MAX = 16
        const val MIN_SPLIT: Long = 8L * 1024 * 1024

        /** [relative] under [root]; a destination that is absolute or steps up a directory is refused. */
        fun destination(root: String, relative: String): String {
            val parts = relative.split('/')
            require(relative.isNotEmpty() && '\\' !in relative && parts.none { it.isEmpty() || it == "." || it == ".." }) { "unsafe destination: $relative" }
            return root.trimEnd('/') + "/" + relative
        }
    }
}

/**
 * An archive.org item as its Metadata API answers (`https://archive.org/metadata/<identifier>`): the data nodes that
 * serve it are its mirrors, and each file's `size` and `sha1` are its pin. Left out: a file the answer gives no
 * `sha1` for (the item's own `_files.xml`), and a file marked `private`, which a lending item's text is and which
 * its data nodes refuse (403). [restricted] is the item's own `access-restricted-item`.
 */
class ArchiveItem(val identifier: String, val metadata: Map<*, *>, val mirrors: Mirrors, val files: List<MirrorFile>, val formats: Map<String, String>) {
    val restricted: Boolean get() = metadata["access-restricted-item"] == "true"

    /** The first file in one of [formats], taken in the order they are given, that is no larger than [bytes]; null when none is. */
    fun file(formats: List<String>, bytes: Long = Long.MAX_VALUE): MirrorFile? {
        for (format in formats) files.firstOrNull { this.formats[it.destination] == format && (it.size ?: Long.MAX_VALUE) <= bytes }?.let { return it }
        return null
    }

    /**
     * Bytes [offset, offset + count) of the file [name], checked against the item's torrent
     * (`<identifier>_archive.torrent`, itself one of the item's pinned files): the whole pieces that cover the range
     * are gathered through [read] (a file's name, an offset in it, a count), each must hash to the torrent's SHA-1
     * for it, and only then is the range handed back. A piece at a file's edge runs into its neighbours in the
     * torrent's order, so [read] is asked for those files' edge bytes as well.
     */
    suspend fun pieces(torrent: TorrentFile, name: String, offset: Long, count: Int, read: suspend (String, Long, Int) -> ByteArray): ByteArray {
        val entry = torrent.files.view.firstOrNull { it.path.joinToString("/") == name } ?: throw IllegalArgumentException("the torrent does not hold $name")
        require(offset >= 0 && count > 0 && offset + count <= entry.length) { "bytes $offset+$count of ${entry.length}" }
        val size = torrent.pieceSize.toLong()
        val from = entry.offset + offset
        val to = from + count
        val out = ByteArray(count)
        for (k in (from / size).toInt()..((to - 1) / size).toInt()) {
            val begin = k * size
            val piece = ByteArray(torrent.pieceLength(k))
            for (e in torrent.files.view) {
                val lo = maxOf(begin, e.offset)
                val hi = minOf(begin + piece.size, e.offset + e.length)
                if (lo < hi && !e.padding) read(e.path.joinToString("/"), lo - e.offset, (hi - lo).toInt()).copyInto(piece, (lo - begin).toInt())
            }
            check(torrent.verifyPiece(k, piece)) { "piece $k of $identifier differs from its hash in the torrent" }
            val lo = maxOf(begin, from)
            piece.copyInto(out, (lo - from).toInt(), (lo - begin).toInt(), (minOf(begin + piece.size, to) - begin).toInt())
        }
        return out
    }

    companion object {
        const val HOST = "https://archive.org"
        /** The format of the item's own torrent. */
        const val TORRENT = "Archive BitTorrent"
        /** The item's text as its OCR gave it, then the formats that hold the same text with markup. */
        val TEXT = listOf("DjVuTXT", "OCR Search Text", "EPUB")

        fun metadata(identifier: String): String = "$HOST/metadata/${percentEncode(identifier)}"

        /** The item of a Metadata API answer; an answer with no `dir` is an item archive.org does not serve (dark, or absent). */
        fun of(json: String): ArchiveItem {
            val raw = reifyMap(json)
            val dir = raw["dir"] as String? ?: throw IllegalArgumentException("archive.org serves no such item")
            val metadata = raw["metadata"] as Map<*, *>
            val identifier = metadata["identifier"] as String
            val servers = (raw["workable_servers"] as List<*>?)?.map { it as String } ?: listOfNotNull(raw["server"] as String?, raw["d1"] as String?, raw["d2"] as String?)
            val formats = LinkedHashMap<String, String>()
            val files = (raw["files"] as List<*>).mapNotNull { row ->
                row as Map<*, *>
                val name = row["name"] as String
                val sha1 = row["sha1"] as String? ?: return@mapNotNull null
                if (row["private"] == "true") return@mapNotNull null
                val destination = "$identifier/$name"
                formats[destination] = row["format"] as String? ?: ""
                MirrorFile("$dir/" + name.split('/').joinToString("/", transform = ::percentEncode), destination, (row["size"] as String?)?.toLongOrNull(), "sha-1=$sha1")
            }
            return ArchiveItem(identifier, metadata, Mirrors(servers.map { "https://$it" }), files, formats)
        }
    }
}

/**
 * One revision of an English Wikipedia article as its Action API names it. A revision's wikitext never changes and
 * the API gives its size and SHA-1, so the revision is a pin: the file is that wikitext, asked for by revision id.
 */
object WikipediaPage {
    const val HOST = "https://en.wikipedia.org"
    val mirrors = Mirrors(listOf(HOST))

    /** The address that describes the current revision of the article [title], a redirect followed to its target. */
    fun query(title: String): String =
        "$HOST/w/api.php?action=query&format=json&formatversion=2&redirects=1&prop=revisions%7Cpageprops&rvprop=ids%7Csize%7Csha1&ppprop=disambiguation&titles=${percentEncode(title)}"

    /** The pinned wikitext of a [query]'s answer; refused when Wikipedia has no such article, or the title is a disambiguation page. */
    fun of(json: String): MirrorFile {
        val page = ((reifyMap(json)["query"] as Map<*, *>)["pages"] as List<*>).single() as Map<*, *>
        val title = page["title"] as String
        require(page["missing"] != true) { "Wikipedia has no article $title" }
        require((page["pageprops"] as Map<*, *>?)?.containsKey("disambiguation") != true) { "$title is a disambiguation page" }
        val revision = (page["revisions"] as List<*>).single() as Map<*, *>
        val id = (revision["revid"] as Number).toLong()
        val name = title.replace(' ', '_')
        return MirrorFile("/w/index.php?title=${percentEncode(name)}&oldid=$id&action=raw", "wikipedia/$name@$id.wiki",
            (revision["size"] as Number).toLong(), "sha-1=${revision["sha1"] as String}")
    }
}

/**
 * A Project Gutenberg ebook by its number. The site and the foundation's own mirror (gutenberg.pglaf.org) serve the
 * same generated files under `/cache/epub/<number>/`: `pg<number>.rdf` describes the book and `pg<number>.txt` is its
 * plain text. Project Gutenberg publishes no checksum, so the pin is the length the description states and no more.
 */
object GutenbergBook {
    val mirrors = Mirrors(listOf("https://www.gutenberg.org", "https://gutenberg.pglaf.org"))

    /** The description of ebook [id], which pins nothing itself. */
    fun description(id: String): MirrorFile {
        require(id.isNotEmpty() && id.all { it in '0'..'9' }) { "a Project Gutenberg ebook is named by its number, not '$id'" }
        return MirrorFile("/cache/epub/$id/pg$id.rdf", "gutenberg/pg$id.rdf")
    }

    /** The plain text of ebook [id] at the length its description [rdf] states; refused when the description lists no plain text. */
    fun of(id: String, rdf: String): MirrorFile {
        val at = rdf.indexOf("/ebooks/$id.txt.utf-8\">")
        require(at >= 0) { "Project Gutenberg ebook $id has no plain text" }
        val end = rdf.indexOf("</pgterms:file>", at)
        val extent = rdf.indexOf("<dcterms:extent", at)
        require(end >= 0 && extent in at until end) { "Project Gutenberg ebook $id: its description states no length for the plain text" }
        val from = rdf.indexOf('>', extent) + 1
        return MirrorFile("/cache/epub/$id/pg$id.txt", "gutenberg/pg$id.txt", rdf.substring(from, rdf.indexOf('<', from)).trim().toLong())
    }
}
