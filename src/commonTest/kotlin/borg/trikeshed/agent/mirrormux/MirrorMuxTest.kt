package borg.trikeshed.agent.mirrormux

import borg.trikeshed.torrent.Sha1
import borg.trikeshed.torrent.TorrentFile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MirrorMuxTest {
    val downloads = """{"schema":"turbofit.downloads/v1",
        "files":{"a":{"repo_id":"org/model","revision":"0123abc","path":"w/model.gguf","destination":"gguf/model.gguf","sha256":"ab12","size_bytes":3000000000},
                 "b":{"repo_id":"org/model","revision":"0123abc","path":"tokenizer.json"}},
        "groups":{"floor":["a","b"]}}"""

    val manifest = """{"schema":"turbofit.artifact-manifest/v1","artifacts":[
        {"id":"x","repo_id":"org/m","revision":"r1","path":"m.gguf","destination":"m.gguf","sha256":"cd34","size_bytes":10,"families":["small","all"]},
        {"repo_id":"org/n","revision":"r2","path":"n.gguf","destination":"","sha256":"ef56","size_bytes":20,"families":["all"]}]}"""

    /** archive.org's Metadata API answer for `dictionaryof_blac_1891_00_20160211`, cut to three of its files. */
    val item = """{"created":1795526368,"d1":"ia601009.us.archive.org","d2":"ia801009.us.archive.org","dir":"/22/items/dictionaryof_blac_1891_00_20160211",
        "files":[{"name":"dictionaryof_blac_1891_00_20160211.epub","format":"EPUB","size":"9494655","sha1":"6607267ebc60877aec763c9e2e5fd9ec086f7779","md5":"c08ccbee68c8cd46555e19784407cee9","source":"derivative"},
                 {"name":"dictionaryof_blac_1891_00_20160211_djvu.txt","format":"DjVuTXT","size":"6294752","sha1":"6789569fdc7a7c17ad5dee0a1a595621b57ed456","md5":"09a9ff24e6a92a7c5c532781eb28e6fd","source":"derivative"},
                 {"name":"dictionaryof_blac_1891_00_20160211_files.xml","format":"Metadata","md5":"5ea59a31705fe47dd16a52545fafc313","source":"original"}],
        "files_count":23,"item_size":1335462772,
        "metadata":{"identifier":"dictionaryof_blac_1891_00_20160211","title":"A dictionary of law","creator":"Black, Henry Campbell, 1860-1927","date":"1891","language":"eng","mediatype":"texts"},
        "server":"ia801009.us.archive.org","workable_servers":["ia801009.us.archive.org","ia601009.us.archive.org"]}"""

    @Test fun aDownloadsCatalogPinsFilesAndGroupsThem() {
        val catalog = MirrorCatalog.of(downloads)
        assertEquals(MirrorFile("/org/model/resolve/0123abc/w/model.gguf", "gguf/model.gguf", 3_000_000_000L, "sha-256=ab12"), catalog.files["a"])
        assertEquals(MirrorFile("/org/model/resolve/0123abc/tokenizer.json", "org--model/tokenizer.json"), catalog.files["b"])
        assertEquals(2, catalog.group("floor").size)
        assertEquals(3_000_000_000L, catalog.bytes("floor"))
        assertFailsWith<IllegalArgumentException> { catalog.group("ceiling") }
        assertFailsWith<IllegalArgumentException> { MirrorCatalog.of("""{"schema":"other/v1"}""") }
    }

    @Test fun aManifestsFamiliesAreItsGroups() {
        val catalog = MirrorCatalog.of(manifest)
        assertEquals(listOf("x", "org/n/n.gguf#1"), catalog.files.keys.toList())
        assertEquals("org--n/n.gguf", catalog.files["org/n/n.gguf#1"]!!.destination)
        assertEquals(mapOf("small" to listOf("x"), "all" to listOf("x", "org/n/n.gguf#1")), catalog.groups)
    }

    @Test fun aFileIsAtEveryMirrorOnceAndItsDownloadCarriesThePin() {
        val mirrors = Mirrors(listOf("https://huggingface.co/", " https://hf-mirror.com", "https://huggingface.co", ""))
        val file = MirrorCatalog.of(downloads).files["a"]!!
        assertEquals(listOf("https://huggingface.co/org/model/resolve/0123abc/w/model.gguf", "https://hf-mirror.com/org/model/resolve/0123abc/w/model.gguf"), mirrors.uris(file))
        val options = mirrors.options(file, "/models/")
        assertEquals("/models/gguf", options.dir)
        assertEquals("model.gguf", options.out)
        assertEquals("sha-256=ab12", options.checksum)
        assertEquals(16, options.split)
        assertEquals(16, options.maxConnectionPerServer)
        assertEquals(8L * 1024 * 1024, options.minSplitSize)
        assertEquals(true, options.continueDownload)
        assertEquals(16, Mirrors((0 until 40).map { "https://m$it.example" }).bases.size)
    }

    @Test fun aRangeIsHandedBackOnlyWhenItsPiecesHashToTheTorrents() = runTest {
        val a = ByteArray(20000) { (it * 31 + 7).toByte() }
        val b = ByteArray(30000) { (it * 17 + 3).toByte() }
        val all = a + b
        val hashes = (0 until 4).map { Sha1.digest(all.copyOfRange(it * 16384, minOf((it + 1) * 16384, all.size))) }.reduce { x, y -> x + y }
        val torrent = TorrentFile.parse("d4:infod5:filesld6:lengthi20000e4:pathl1:aeed6:lengthi30000e4:pathl1:beee4:name1:x12:piece lengthi16384e6:pieces80:".encodeToByteArray() + hashes + "ee".encodeToByteArray())
        val archive = ArchiveItem("x", emptyMap<Any?, Any?>(), Mirrors(emptyList()), emptyList(), emptyMap())
        val asked = ArrayList<String>()
        var changed = false
        val read: suspend (String, Long, Int) -> ByteArray = { name, at, n ->
            asked.add("$name $at+$n")
            (if (name == "a") a else b).copyOfRange(at.toInt(), at.toInt() + n).also { if (changed) it[0] = (it[0] + 1).toByte() }
        }
        assertContentEquals(b.copyOfRange(100, 150), archive.pieces(torrent, "b", 100, 50, read))
        assertEquals(listOf("a 16384+3616", "b 0+12768"), asked, "the piece at the files' edge is gathered from both")
        asked.clear()
        assertContentEquals(b.copyOfRange(12000, 14000), archive.pieces(torrent, "b", 12000, 2000, read))
        assertEquals(listOf("a 16384+3616", "b 0+12768", "b 12768+16384"), asked)
        changed = true
        assertFailsWith<IllegalStateException> { archive.pieces(torrent, "b", 100, 50, read) }
        assertFailsWith<IllegalArgumentException> { archive.pieces(torrent, "c", 0, 1, read) }
    }

    @Test fun aDestinationStaysUnderItsRoot() {
        assertEquals("/r/a/b.txt", Mirrors.destination("/r", "a/b.txt"))
        for (unsafe in listOf("/etc/passwd", "../x", "a/../../x", "a//b", "", "a\\..\\b", "./a"))
            assertFailsWith<IllegalArgumentException>(unsafe) { Mirrors.destination("/r", unsafe) }
    }

    @Test fun anArchiveItemsDataNodesAreItsMirrorsAndItsSha1sItsPins() {
        val archive = ArchiveItem.of(item)
        assertEquals("dictionaryof_blac_1891_00_20160211", archive.identifier)
        assertEquals(listOf("https://ia801009.us.archive.org", "https://ia601009.us.archive.org"), archive.mirrors.bases)
        assertEquals(2, archive.files.size, "the file with no sha1 is left out")
        val text = archive.file(ArchiveItem.TEXT)!!
        assertEquals(MirrorFile("/22/items/dictionaryof_blac_1891_00_20160211/dictionaryof_blac_1891_00_20160211_djvu.txt",
            "dictionaryof_blac_1891_00_20160211/dictionaryof_blac_1891_00_20160211_djvu.txt", 6294752L, "sha-1=6789569fdc7a7c17ad5dee0a1a595621b57ed456"), text)
        assertEquals("https://ia601009.us.archive.org/22/items/dictionaryof_blac_1891_00_20160211/dictionaryof_blac_1891_00_20160211_djvu.txt", archive.mirrors.uris(text)[1])
        assertNull(archive.file(ArchiveItem.TEXT, 1_000_000L), "nothing fits a megabyte")
        assertEquals("EPUB", archive.formats[archive.file(listOf("EPUB"))!!.destination])
        assertEquals("https://archive.org/metadata/dictionaryof_blac_1891_00_20160211", ArchiveItem.metadata("dictionaryof_blac_1891_00_20160211"))
        assertFailsWith<IllegalArgumentException> { ArchiveItem.of("""{"metadata":{}}""") }
    }
}
