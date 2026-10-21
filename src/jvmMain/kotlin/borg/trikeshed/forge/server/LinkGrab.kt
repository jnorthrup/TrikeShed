package borg.trikeshed.forge.server

import borg.trikeshed.htx.HtxKey
import borg.trikeshed.htx.HtxMethod
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.headerValue
import borg.trikeshed.htx.htxHeaders
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.lcnc.ProjectCorpus
import borg.trikeshed.lcnc.ProjectNodes
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toArray
import borg.trikeshed.narsese.ConstellationNodes
import borg.trikeshed.narsese.DecisionProvider
import borg.trikeshed.userspace.nio.channels.spi.EgressAllowlist
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * The documents a curated document links to, each brought in as a document of the same group: a link the document's
 * Jev tables hold referenced is fetched and lands in the citing document's project under its publisher's host
 * (`<host>/<path>`), its own book beside the root document, never text inside the citing document's pages. A notes twin
 * says where it came from: `url`, `retrieved`, `cited-by` (the citing document's address), `cited-in` (its id), `anchor`
 * and `depth`. The landed document curates through the intake like every upload, so its own referenced links land in
 * the group one level further, until [DEPTH].
 */
class LinkGrab(
    private val nodes: ConstellationNodes,
    private val corpus: ProjectCorpus,
    private val scopes: ProjectScopes,
    private val context: CoroutineContext,
) {
    /** Per group, the works it holds: canonical addresses ([work]) and content digests, read once from its notes. */
    private class Held(val works: MutableSet<String>, val digests: MutableSet<String>)
    private val held = ConcurrentHashMap<String, Held>()

    private suspend fun held(project: String): Held = held[project] ?: run {
        val h = Held(ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet())
        for (d in corpus.docs(project, limit = Int.MAX_VALUE)) {
            val n = corpus.read(project, d.id + ProjectNodes.NOTES_SUFFIX, 4096)?.text?.let(ProjectNodes::conventions) ?: continue
            listOfNotNull(n["url"], n["requested"]).forEach { h.works.add(work(it)) }
            n["sha256"]?.let { h.digests.add(it) }
        }
        held.putIfAbsent(project, h) ?: h
    }

    suspend fun grab(project: String, id: String, provider: DecisionProvider?) {
        if (provider == null) return
        val notes = corpus.read(project, id + ProjectNodes.NOTES_SUFFIX, 262_144)?.text?.let(ProjectNodes::conventions).orEmpty()
        val depth = notes["depth"]?.toIntOrNull() ?: 0
        if (depth >= DEPTH) return
        val citing = notes["url"] ?: "$project/$id"
        // The date the citing document speaks as of, carried to what it cites: a refused link is read as archived then.
        val asOf = (notes["as-of"] ?: notes["date"])?.filter(Char::isDigit)?.take(8)?.takeIf { it.length == 8 }
        val links = nodes.forProvider(provider).referenced(id).take(GRABS)
        // A work the group already holds (the root, an ancestor, a sibling under another address) is not grabbed again:
        // a link back up the citation chain closes a cycle, and a second address of one work is an echo of it.
        val h = held(project)
        var landed = 0; var echoes = 0
        for (l in links) {
            val href = l["href"].toString().substringBefore('#')
            if (!h.works.add(work(href))) { echoes++; continue }
            runCatching { if (land(project, href, l["anchor"]?.toString().orEmpty(), citing, "$project/$id", depth + 1, asOf, provider, h)) landed++ else echoes++ }
                .onFailure { System.err.println("[GRAB] $href: ${it.message ?: it}") }
        }
        System.err.println("[GRAB] $project/$id: ${links.size} referenced, $landed landed, $echoes held already (depth ${depth + 1})")
    }

    /**
     * A dragged link: its document fetched and landed as the root of a group of its own, named for its host and path,
     * at `<host>/<path>` like every document its links reach. Answers the group and the root's id.
     */
    suspend fun root(url: String, provider: DecisionProvider?, requirePdf: Boolean = false): Pair<String, String> {
        val host = name(host(url) ?: error("not a web address: $url"))
        val group = (host + "-" + stem(url).lowercase()).replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.').take(64).trimEnd('-', '.')
        val (bytes, type, at) = fetch(url)
        val ext = extension(bytes, type, requirePdf)
        val digest = borg.trikeshed.job.ContentId.of(bytes).hex
        if (scopes.list().none { it.name == group }) scopes.beginUpload(group, "assets", provider)
        held(group).let { h -> h.works.add(work(url)); h.works.add(work(at)); h.digests.add(digest) }
        val doc = "$host/${stem(url)}.$ext"
        val twin = buildString {
            append("work: ").append(at).append('\n').append("url: ").append(at).append('\n')
            if (at != url) append("requested: ").append(url).append('\n')
            append("retrieved: ").append(java.time.OffsetDateTime.now()).append('\n')
            append("sha256: ").append(digest).append('\n')
            append("bytes: ").append(bytes.size).append('\n').append("type: ").append(type).append('\n')
            append("depth: 0\n")
        }
        scopes.uploadPut(group, doc + ProjectNodes.NOTES_SUFFIX, twin.encodeToByteArray(), provider)
        scopes.uploadPut(group, doc, bytes, provider)
        return group to doc
    }

    /**
     * One link fetched and landed in the citing document's group; false when the group holds it or it is neither markup
     * nor PDF. A link its host refuses (a bot wall, a page since moved) is read from the Wayback Machine's capture
     * nearest [asOf], the date the citing document speaks as of, and that capture is its `source`.
     */
    private suspend fun land(project: String, href: String, anchor: String, citing: String, citedIn: String, depth: Int,
                             asOf: String?, provider: DecisionProvider, h: Held): Boolean {
        val stem = name(host(href) ?: return false) + "/" + stem(href)
        if (listOf("html", "pdf").any { corpus.read(project, "$stem.$it${ProjectNodes.NOTES_SUFFIX}", 16) != null }) return false
        val (bytes, type, at) = runCatching { fetch(href) }.getOrElse { refused ->
            runCatching { fetch("$WAYBACK/${asOf ?: "2"}id_/$href") }.getOrElse { throw refused }
        }
        val archived = at.startsWith(WAYBACK)
        val ext = when {
            "pdf" in type -> "pdf"
            "html" in type -> "html"
            else -> return false
        }
        // A redirect onto a held work, or bytes the group already holds under another address, is that work again.
        val digest = borg.trikeshed.job.ContentId.of(bytes).hex
        if (!archived && work(at) != work(href) && !h.works.add(work(at))) return false
        if (!h.digests.add(digest)) return false
        val doc = "$stem.$ext"
        val twin = buildString {
            val url = if (archived) href else at
            append("work: ").append(url).append('\n').append("url: ").append(url).append('\n')
            if (archived) append("source: ").append(at).append('\n') else if (at != href) append("requested: ").append(href).append('\n')
            append("retrieved: ").append(java.time.OffsetDateTime.now()).append('\n')
            asOf?.let { append("as-of: ").append(it).append('\n') }
            append("sha256: ").append(digest).append('\n')
            append("bytes: ").append(bytes.size).append('\n').append("type: ").append(type).append('\n')
            append("cited-by: ").append(citing).append('\n').append("cited-in: ").append(citedIn).append('\n')
            if (anchor.isNotBlank()) append("anchor: ").append(anchor.replace('\n', ' ')).append('\n')
            append("depth: ").append(depth).append('\n')
        }
        scopes.uploadPut(project, doc + ProjectNodes.NOTES_SUFFIX, twin.encodeToByteArray(), provider)
        scopes.uploadPut(project, doc, bytes, provider)
        return true
    }

    /** [url]'s bytes, content type and final address, following redirects over the daemon's HTX route. */
    private suspend fun fetch(url: String): Triple<ByteArray, String, String> = withContext(context.minusKey(Job)) {
        var at = url
        repeat(REDIRECTS) {
            EgressAllowlist.allowUrl(at)
            val htx = currentCoroutineContext()[HtxKey] ?: error("no HTX route in the daemon context")
            val req = (parseHtxRequest(url = at, method = HtxMethod.GET) as HtxRequest)
                .copy(headers = htxHeaders("User-Agent" j AGENT, "Accept" j "text/html,application/xhtml+xml,application/pdf"))
            val resp = withTimeout(FETCH_MS) { htx.request(req) }
            when (resp.status) {
                in 200..299 -> return@withContext Triple(resp.body.toArray(), resp.headers.headerValue("Content-Type").orEmpty().lowercase(), at)
                in 300..399 -> at = resolve(at, resp.headers.headerValue("Location") ?: error("redirect ${resp.status} without Location"))
                else -> error("HTTP ${resp.status}")
            }
        }
        error("more than $REDIRECTS redirects")
    }

    companion object {
        /** Runs before beginUpload, CAS retention, notes, or intake notification. */
        internal fun extension(bytes: ByteArray, type: String, requirePdf: Boolean): String {
            val mime = type.substringBefore(';').trim().lowercase()
            if (mime == "application/pdf") {
                require(bytes.size >= 8 && bytes.copyOfRange(0, 5).decodeToString() == "%PDF-" &&
                    bytes[5].toInt().toChar() in '1'..'2' && bytes[6].toInt().toChar() == '.' &&
                    bytes[7].toInt().toChar().isDigit()) { "PDF content does not carry a PDF header" }
                return "pdf"
            }
            require(!requirePdf) { "PDF intake requires application/pdf; received $type" }
            require(mime in setOf("text/html", "application/xhtml+xml")) { "neither markup nor PDF: $type" }
            return "html"
        }

        /** Links grabbed per document, levels of links followed, redirects followed, and the wait per fetch. */
        const val GRABS = 64
        const val DEPTH = 2
        const val REDIRECTS = 5
        const val FETCH_MS = 45_000L
        const val AGENT = "TrikeShed-curation/0.1 (+https://github.com/jnorthrup/TrikeShed)"
        const val WAYBACK = "https://web.archive.org/web"

        private val HOST = Regex("^[A-Za-z][A-Za-z0-9+.-]*://([^/?#:]+)")
        fun host(url: String): String? = HOST.find(url)?.groupValues?.get(1)?.lowercase()
        fun name(host: String) = host.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.')

        /**
         * The work an address names, for telling one work under two addresses: host without `www.`, path without a leading
         * language segment (`/en/`, `/en-gb/`) or trailing slash, query without language or tracking parameters, no fragment.
         */
        fun work(url: String): String {
            val u = url.substringBefore('#')
            val host = host(u)?.removePrefix("www.") ?: return u
            val path = LOCALE.replace("/" + u.substringAfter("://").substringAfter('/', "").substringBefore('?'), "/").trimEnd('/')
            val query = u.substringAfter('?', "").split('&')
                .filter { p -> p.isNotEmpty() && p.substringBefore('=').lowercase().let { it !in UNTRACKED && !it.startsWith("utm_") } }
                .sorted().joinToString("&")
            return host + path + (if (query.isEmpty()) "" else "?$query")
        }
        private val LOCALE = Regex("^/(?:en|fr|de|es|it|pt|nl|ja|ko|zh|ru|pl|sv|da|nb|fi|tr|he)(?:-[a-zA-Z]{2,4})?(?=/|$)")
        private val UNTRACKED = setOf("locale", "lang", "language", "hl", "ref", "source")

        /** A document id from [url]'s path and query: short enough for any file system, distinct by a digest tail. */
        fun stem(url: String): String {
            val rest = url.substringAfter("://").substringAfter('/', "").substringBefore('#').trim('/')
            val flat = rest.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.').ifEmpty { "index" }
                .let { s -> s.substringBeforeLast('.').takeIf { s.substringAfterLast('.', "").lowercase() in setOf("html", "htm", "pdf", "aspx", "asp", "php") } ?: s }
            return if (flat.length <= 48) flat
            else flat.take(40).trimEnd('_', '-', '.') + "-" + borg.trikeshed.job.ContentId.of(url.encodeToByteArray()).hex.take(7)
        }

        fun resolve(from: String, location: String): String = when {
            location.contains("://") -> location
            location.startsWith("//") -> from.substringBefore(':') + ":" + location
            location.startsWith("/") -> (HOST.find(from)?.value ?: "") + location
            else -> from.substringBeforeLast('/') + "/" + location
        }
    }
}
