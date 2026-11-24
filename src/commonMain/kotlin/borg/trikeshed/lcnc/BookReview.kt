package borg.trikeshed.lcnc

import borg.trikeshed.agent.mirrormux.*
import borg.trikeshed.agent.modelmux.Airgap
import borg.trikeshed.cli.htx.HtxAria2Engine
import borg.trikeshed.lib.*
import borg.trikeshed.parse.aac.*
import borg.trikeshed.parse.reify

/**
 * What may supply a scenario's prerequisites, and how much of it: the sources by name, the files the work's own
 * archive.org item may bring and their formats, the wanted one first, the Wikipedia [articles] by title, the
 * Project Gutenberg [ebooks] by number, and the bytes any one file may run to. A source not among [sources] is not asked.
 */
data class PrerequisiteBound(val sources: Set<String> = setOf(BookReview.ARCHIVE), val files: Int = 1, val bytes: Long = 16L shl 20,
                             val formats: List<String> = ArchiveItem.TEXT, val articles: List<String> = emptyList(),
                             val ebooks: List<String> = emptyList())

/** One pinned file a reviewer reads first, the source it comes from and the mirrors that serve it. */
class Prerequisite(val source: String, val file: MirrorFile, val mirrors: Mirrors)

/**
 * What a review must find, and who says so. A person's [claim] is the truth a review is judged by; a model's is a
 * candidate, itself judged against the persons' claims.
 */
data class Expectation(val author: String, val claim: String) {
    val standing: String get() = if (author == PERSON) "truth" else "candidate"

    companion object {
        const val PERSON = "person"
        const val MODEL = "model"

        /**
         * Expectations as rows of `{"author": "person" | "model", "claim": …}`, a list or its JSON text. A row with
         * neither field filled is passed over; any other author, or a claim left empty, is refused.
         */
        fun rows(value: Any?): List<Expectation> {
            val rows = (if (value is CharSequence) value.takeIf { it.isNotBlank() }?.let { reify(it) } else value) ?: return emptyList()
            require(rows is List<*>) { "expectations are a list of rows" }
            return rows.mapNotNull { row ->
                require(row is Map<*, *>) { "an expectation is a row with an author and a claim" }
                val author = row["author"]?.toString()?.trim().orEmpty()
                val claim = row["claim"]?.toString()?.trim().orEmpty()
                if (author.isEmpty() && claim.isEmpty()) return@mapNotNull null
                require(author == PERSON || author == MODEL) { "an expectation's author is $PERSON or $MODEL, not '$author'" }
                require(claim.isNotEmpty()) { "an expectation of a $author states no claim" }
                Expectation(author, claim)
            }
        }
    }
}

/**
 * A book review scenario: the [work] one container of Anna's Archive's index describes, and the [prerequisites]
 * a reviewer reads first. Anna's Archive supplies the index line and nothing else; a prerequisite comes from a
 * source the [bound] selects: from archive.org only what it serves openly, from Wikipedia the articles the bound
 * names, each at its current revision, from Project Gutenberg the plain text of the ebooks it names. A prerequisite
 * is pinned by size and checksum, a Project Gutenberg text by size alone. [withheld] says why a source, an article
 * or an ebook supplied nothing; a source that cannot be reached fails the scenario. The scenario forms no
 * expectation of its own: [expectations] are what a person or a model supplied, each under its author.
 */
class BookReview(val record: AacId, val work: Map<String, Any?>, val bound: PrerequisiteBound,
                 val prerequisites: List<Prerequisite>, val withheld: Map<String, String>,
                 val expectations: List<Expectation> = emptyList()) {

    /** This scenario with [expected] as what a review of it must find. */
    fun expecting(expected: List<Expectation>): BookReview = BookReview(record, work, bound, prerequisites, withheld, expected)

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "schema" to SCHEMA, "task" to "review", "record" to record.toString(), "work" to work,
        "bound" to linkedMapOf("sources" to bound.sources.toList(), "files" to bound.files, "bytes" to bound.bytes, "formats" to bound.formats, "articles" to bound.articles, "ebooks" to bound.ebooks),
        "prerequisites" to prerequisites.map {
            linkedMapOf("source" to it.source, "destination" to it.file.destination, "size" to it.file.size, "checksum" to it.file.checksum, "uris" to it.mirrors.uris(it.file))
        },
        "withheld" to withheld,
        "expectations" to expectations.map { linkedMapOf("author" to it.author, "standing" to it.standing, "claim" to it.claim) })

    companion object {
        const val SCHEMA = "trikeshed.book-review/v1"
        const val ARCHIVE = "archive.org"
        const val WIKIPEDIA = "wikipedia"
        const val GUTENBERG = "gutenberg"
        /** The collection whose containers are archive.org's own answers: `{"ia_id": …, "metadata_json": {…}}`. */
        const val COLLECTION = "ia2_records"
        /** What of archive.org's description of an item names the work. */
        val FIELDS = listOf("title", "creator", "date", "language", "publisher", "isbn", "oclc-id", "openlibrary_edition", "openlibrary_work")

        /** The work an `ia2_records` container describes: its archive.org identifier and the [FIELDS] its description gives. */
        fun work(record: AacRecord): Map<String, Any?> {
            require(record.id.collection == COLLECTION) { "no reading of collection ${record.id.collection}: $COLLECTION is read" }
            val container = record.metadata as? Map<*, *> ?: error("${record.id}: metadata is not an object")
            val described = (container["metadata_json"] as? Map<*, *>)?.get("metadata") as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val work = linkedMapOf<String, Any?>("identifier" to (container["ia_id"] as? String ?: error("${record.id}: no ia_id")))
            for (field in FIELDS) described[field]?.let { work[field] = it }
            return work
        }

        /**
         * The scenario of [record] within [bound]. [fetch] answers the bytes its URIs all name; what is asked is the
         * item's Metadata API address, one Action API address an article and one description an ebook, each only when
         * the bound selects its source.
         */
        suspend fun of(record: AacRecord, bound: PrerequisiteBound = PrerequisiteBound(), fetch: MirrorFetch): BookReview {
            val work = work(record)
            val found = ArrayList<Prerequisite>()
            val withheld = LinkedHashMap<String, String>()
            if (ARCHIVE !in bound.sources) withheld[ARCHIVE] = "not selected" else try {
                val item = ArchiveItem.of(fetch(listOf(ArchiveItem.metadata(work["identifier"] as String))).decodeToString())
                for (format in bound.formats) {
                    if (found.size >= bound.files) break
                    item.file(listOf(format), bound.bytes)?.let { found += Prerequisite(ARCHIVE, it, item.mirrors) }
                }
                if (found.isEmpty()) withheld[ARCHIVE] =
                    if (item.restricted) "the item is lent, not served: none of ${bound.formats} is open" else "none of ${bound.formats} within ${bound.bytes} bytes"
            } catch (absent: IllegalArgumentException) { withheld[ARCHIVE] = absent.message.orEmpty() }
            for (title in bound.articles) {
                val key = "$WIKIPEDIA: $title"
                if (WIKIPEDIA !in bound.sources) { withheld[key] = "not selected"; continue }
                try {
                    val file = WikipediaPage.of(fetch(listOf(WikipediaPage.query(title))).decodeToString())
                    if ((file.size ?: 0L) > bound.bytes) withheld[key] = "${file.size} bytes, over the bound's ${bound.bytes}"
                    else found += Prerequisite(WIKIPEDIA, file, WikipediaPage.mirrors)
                } catch (refused: IllegalArgumentException) { withheld[key] = refused.message.orEmpty() }
            }
            for (id in bound.ebooks) {
                val key = "$GUTENBERG: $id"
                if (GUTENBERG !in bound.sources) { withheld[key] = "not selected"; continue }
                try {
                    val file = GutenbergBook.of(id, fetch(GutenbergBook.mirrors.uris(GutenbergBook.description(id))).decodeToString())
                    if ((file.size ?: 0L) > bound.bytes) withheld[key] = "${file.size} bytes, over the bound's ${bound.bytes}"
                    else found += Prerequisite(GUTENBERG, file, GutenbergBook.mirrors)
                } catch (refused: IllegalArgumentException) { withheld[key] = refused.message.orEmpty() }
            }
            return BookReview(record.id, work, bound, found, withheld)
        }

        /** `book.review` on the canvas, and the prefab that offers it. */
        const val TYPE = "book.review"
        const val NAME = "preset-book-review"
        val SOURCES = setOf(ARCHIVE, WIKIPEDIA, GUTENBERG)

        /**
         * The bound a node's params state: `sources` and `formats` separated by commas, `articles` one title a line,
         * `ebooks` one number a line, `files` and `bytes` whole numbers. A param left out keeps [PrerequisiteBound]'s own value; `sources` given
         * empty selects none.
         */
        fun bound(params: Map<String, String>): PrerequisiteBound {
            fun items(name: String, cut: Char) = params[name]?.split(cut)?.map { it.trim() }?.filter { it.isNotEmpty() }
            fun number(name: String) = params[name]?.takeIf { it.isNotBlank() }?.let {
                it.trim().toLongOrNull()?.takeIf { n -> n > 0 } ?: throw IllegalArgumentException("$TYPE: $name is a whole number above zero, not '$it'")
            }
            val standing = PrerequisiteBound()
            val sources = items("sources", ',')?.toSet() ?: standing.sources
            require(SOURCES.containsAll(sources)) { "$TYPE: no source ${sources - SOURCES}; the sources are $SOURCES" }
            return PrerequisiteBound(sources, number("files")?.toInt() ?: standing.files, number("bytes") ?: standing.bytes,
                items("formats", ',')?.takeIf { it.isNotEmpty() } ?: standing.formats, items("articles", '\n') ?: standing.articles, items("ebooks", '\n') ?: standing.ebooks)
        }

        /** The node over [fetch], a host's or the caller's own [MirrorFetchKey]. Under an airgap it fails at the first URL it would ask. */
        fun registry(fetch: MirrorFetch): Map<String, LcncNodeRunner> = mapOf(
            TYPE to boundLcnc(MirrorFetchKey(fetch)) { service, node, inputs ->
                val line = ((inputs["record"] ?: inputs["record?"])?.toString() ?: node.params["record"])?.takeIf { it.isNotBlank() }
                    ?: error("$TYPE: no index record")
                val expected = try { Expectation.rows(inputs["expectations"] ?: inputs["expectations?"] ?: node.params["expectations"]) }
                    catch (refused: IllegalArgumentException) { throw IllegalArgumentException("$TYPE: ${refused.message}") }
                val review = of(Aac.record(line.trim().encodeToByteArray()), bound(node.params)) { uris -> remote(TYPE, service.value, uris) }
                val document = review.expecting(expected).toMap()
                mapOf("scenario" to document, "prerequisites" to document["prerequisites"], "withheld" to document["withheld"], "expectations" to document["expectations"])
            },
            PASSAGE to boundLcnc(MirrorFetchKey(fetch)) { service, node, inputs ->
                fun words(name: String, what: String) = node.params[name]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$PASSAGE: name the words $what")
                val rows = (inputs["prerequisites"] ?: inputs["prerequisites?"]) as? List<*> ?: error("$PASSAGE: no prerequisites")
                val ordinal = node.params["prerequisite"]?.takeIf { it.isNotBlank() }?.let {
                    it.trim().toIntOrNull() ?: throw IllegalArgumentException("$PASSAGE: prerequisite is an ordinal, not '$it'")
                } ?: 0
                val row = rows.getOrNull(ordinal) as? Map<*, *> ?: throw IllegalArgumentException("$PASSAGE: no prerequisite $ordinal among ${rows.size}")
                val from = words("from", "the passage starts with")
                val until = words("until", "that follow the passage")
                val bytes = remote(PASSAGE, service.value, (row["uris"] as List<*>).map { it as String })
                HtxAria2Engine.verify(bytes, row["checksum"] as String?, (row["size"] as Number?)?.toLong())
                val whole = bytes.decodeToString()
                val begin = marker(from).find(whole) ?: throw IllegalArgumentException("$PASSAGE: '$from' is not in ${row["destination"]}")
                val end = marker(until).find(whole, begin.range.last + 1) ?: throw IllegalArgumentException("$PASSAGE: '$until' does not follow '$from' in ${row["destination"]}")
                val text = whole.substring(begin.range.first, end.range.first)
                require(text.length <= PASSAGE_CHARS) { "$PASSAGE: ${text.length} characters from '$from' to '$until', over $PASSAGE_CHARS: name a nearer end" }
                mapOf("text" to text, "range" to linkedMapOf("destination" to row["destination"], "checksum" to row["checksum"],
                    "offset" to whole.substring(0, begin.range.first).encodeToByteArray().size, "length" to text.encodeToByteArray().size))
            })

        /** `prerequisite.passage`: the part of one prerequisite, fetched whole and checked against its pin, that lies between two markers. */
        const val PASSAGE = "prerequisite.passage"
        /** The longest passage cut, in characters. */
        const val PASSAGE_CHARS = 1 shl 16

        /** [words] as they stand in a text whose spacing and line breaks differ: any run of whitespace matches any other. */
        private fun marker(words: String) = Regex(words.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) })

        private suspend fun remote(type: String, fetch: MirrorFetch, uris: List<String>): ByteArray {
            check(!Airgap.active()) { "$type: ${uris.first()} is remote, and the airgap is on" }
            return fetch(uris)
        }

        fun passage() = LcncPortContract(PASSAGE, "prerequisites → one of them fetched, checked against its pin, and cut between two markers",
            _l["prerequisites"], _l["text", "range"],
            inputKinds = _m["prerequisites" j "json"],
            outputKinds = _m["text" j "text", "range" j "json"],
            params = _m[
                "prerequisite" j LcncPortContract.LcncParamSpec(v = "0", ph = "Which prerequisite, counted from 0"),
                "from" j LcncPortContract.LcncParamSpec(ph = "The words the passage starts with"),
                "until" j LcncPortContract.LcncParamSpec(ph = "The words that follow the passage")])

        fun contract() = LcncPortContract(TYPE, "one index record → a book review scenario with bounded, pinned prerequisites",
            _l["record?", "expectations?"], _l["scenario", "prerequisites", "withheld", "expectations"],
            inputKinds = _m["record" j "text", "expectations" j "json"],
            outputKinds = _m["scenario" j "json", "prerequisites" j "json", "withheld" j "json", "expectations" j "json"],
            params = _m[
                "record" j LcncPortContract.LcncParamSpec(ta = true, ph = "One line of an Anna's Archive $COLLECTION metadata file, when no cable carries it"),
                "sources" j LcncPortContract.LcncParamSpec(v = ARCHIVE, ph = "$ARCHIVE, $WIKIPEDIA, $GUTENBERG"),
                "files" j LcncPortContract.LcncParamSpec(v = "1", ph = "Files the work's own item may bring"),
                "bytes" j LcncPortContract.LcncParamSpec(v = (16L shl 20).toString(), ph = "The most one file may run to"),
                "formats" j LcncPortContract.LcncParamSpec(v = ArchiveItem.TEXT.joinToString(", "), ph = "archive.org formats, the wanted one first"),
                "articles" j LcncPortContract.LcncParamSpec(ta = true, ph = "Wikipedia article titles, one a line"),
                "ebooks" j LcncPortContract.LcncParamSpec(ta = true, ph = "Project Gutenberg ebook numbers, one a line"),
                "expectations" j LcncPortContract.LcncParamSpec(cols = _l["author", "claim"], optional = true)])

        val info = LcncPresetInfo(NAME, "Prepare a book review",
            "Turns one line of a book index into a review task and lists the reading a reviewer needs first, each file with its size and checksum.",
            "One line of an Anna's Archive metadata file for the Internet Archive collection, and a network connection. A book the Internet Archive only lends brings no text.",
            "The task as a document, the reading it found with the addresses that serve it, and the reason for anything it left out.",
            "Paste the index line into the record box. To add encyclopedia articles or Project Gutenberg books, name them on the review node and add wikipedia or gutenberg to its sources. List what the review must find under expectations.")

        fun build(): LcncProgram = LcncProgram(NAME, s_[
            LcncNode("instructions", "note", _m["text" j "Paste one line of an Anna's Archive $COLLECTION metadata file into the record box and Run. The review node asks the Internet Archive what it serves openly for that book and pins each file by size and checksum; nothing is downloaded here. Sources, file count, size and formats are the node's bound. What a review must find goes in the node's expectations, one row each: the author is person or model. A person's row is the truth a review is judged by; a model's is a candidate."], x = 40.0, y = 20.0),
            LcncNode("record", "text.value", _m["value" j ""], x = 40.0, y = 230.0),
            LcncNode("review", TYPE, x = 390.0, y = 230.0),
            LcncNode("scenario", "display", x = 740.0, y = 60.0),
            LcncNode("prerequisites", "display", x = 740.0, y = 260.0),
            LcncNode("withheld", "display", x = 740.0, y = 460.0),
        ], s_[LcncWire("record", "value", "review", "record?"), LcncWire("review", "scenario", "scenario", "x"),
            LcncWire("review", "prerequisites", "prerequisites", "x"), LcncWire("review", "withheld", "withheld", "x")],
            view = LcncView(x = 20.0, y = 20.0, zoom = 0.65), seq = 6)
    }
}
