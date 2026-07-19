package borg.trikeshed.lcnc.reactor

import borg.trikeshed.lcnc.ccek.IngestStateElement
import borg.trikeshed.lcnc.isam.LcncBlock
import borg.trikeshed.lcnc.isam.LcncDatabase
import borg.trikeshed.lcnc.isam.LcncEntity
import borg.trikeshed.lcnc.isam.LcncPage
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The CCEK lifecycle of an [LcncIngestPipeline] run.
 *
 * The pipeline owns its lifecycle; the [IngestStateElement] only collects
 * entities. Lifecycle transitions are announced over the [ReactorAction]
 * channel so downstream observers (e.g. reducers in
 * [borg.trikeshed.lcnc.reduction]) can mirror CREATED → OPEN → ACTIVE →
 * DRAINING → CLOSED without reflection into private state.
 */
enum class IngestLifecycle { CREATED, OPEN, ACTIVE, DRAINING, CLOSED }

/**
 * Lifecycle events that flow through the [LcncIngestPipeline] fanout.
 *
 * A single ingest run produces one or more [ReactorAction.PublishEntity]
 * events (one per parsed [LcncEntity]) interleaved with lifecycle
 * announcements ([ReactorAction.Opened], [ReactorAction.Activated],
 * [ReactorAction.Draining], [ReactorAction.Closed]).
 *
 * Subscribers — including the [IngestStateElement] — observe these
 * through the [LcncIngestPipeline.actions] channel instead of a stale
 * `mutableListOf` accumulator.
 */
sealed class ReactorAction {
    /** Pipeline opened: CREATED → OPEN. */
    data object Opened : ReactorAction()

    /** Pipeline is actively emitting entities: OPEN → ACTIVE. */
    data object Activated : ReactorAction()

    /** A parsed entity is ready for downstream consumers. */
    data class PublishEntity(val entity: LcncEntity) : ReactorAction()

    /** Pipeline accepts no more decoding requests: ACTIVE → DRAINING. */
    data object Draining : ReactorAction()

    /** Pipeline closed: DRAINING → CLOSED. */
    data object Closed : ReactorAction()
}

/**
 * Concrete [IngestCodec] that decodes an [IngestSource] into a
 * [Series] of [LcncEntity] and fans the work out over a buffered
 * [Channel] of [ReactorAction] events.
 *
 * Supported formats: every entry of [IngestFormat].
 *
 * Wire-up pattern:
 * ```
 * val state = IngestStateElement("run-1")
 * val pipe = LcncIngestPipeline(state)
 * val entities = pipe.decode(IngestSource.Paste(csv), IngestFormat.CSV)
 * // entities: Series<LcncEntity>
 * pipe.drain()
 * pipe.close()
 * ```
 *
 * The decoder always:
 *   1. materializes the [IngestSource] to text bytes,
 *   2. parses per [IngestFormat] into a list of [LcncEntity] values,
 *   3. emits a [ReactorAction.PublishEntity] for each parsed entity —
 *      the [IngestStateElement] consumes these via [installFanout] and
 *      appends to its own view,
 *   4. returns the same list as a TrikeShed [Series] (size-coupled
 *      index oracle).
 *
 * The pipeline's own lifecycle ([IngestLifecycle]) is independent from
 * the [IngestStateElement]'s accumulator — lifecycle events ride the
 * same [ReactorAction] channel, so observers can mirror them without
 * reflection.
 */
class LcncIngestPipeline(
    private val state: IngestStateElement,
    private val bufferCapacity: Int = Channel.BUFFERED,
    parentJob: Job? = null,
) : IngestCodec {

    override val supportedFormats: Set<IngestFormat> =
        setOf(
            IngestFormat.CSV,
            IngestFormat.TSV,
            IngestFormat.MARKDOWN,
            IngestFormat.HTML,
            IngestFormat.JSON,
            IngestFormat.LCNC_NATIVE,
        )

    /** Buffered fanout channel — every parsed entity + lifecycle hop is offered here. */
    private val channel: Channel<ReactorAction> = Channel(bufferCapacity)

    /**
     * Background scope that drains the fanout and feeds [state]. Cancelled on close.
     * Detached from any caller scope so the pipeline can outlive short-lived
     * coroutines that triggered a decode.
     */
    private val fanoutScope: CoroutineScope =
        CoroutineScope(SupervisorJob(parentJob) + Dispatchers.Default)

    /** Current CCEK lifecycle state of this pipeline. */
    var lifecycleState: IngestLifecycle = IngestLifecycle.CREATED
        private set

    /** Live stream of [ReactorAction] events. */
    val actions: Channel<ReactorAction> get() = channel

    init {
        installFanout()
    }

    /**
     * Connects the [channel] to [state]'s accumulator. Each
     * [ReactorAction.PublishEntity] is recorded into [state]; lifecycle
     * hops simply update [lifecycleState] on the pipeline.
     *
     * Multiple subscribers may attach externally by reading from
     * [actions]; the fanout consumer loop here mirrors only the
     * in-process [IngestStateElement] binding.
     */
    private fun installFanout() {
        fanoutScope.launch {
            try {
                channel.consumeEach { action ->
                    when (action) {
                        is ReactorAction.PublishEntity -> state.publishEntity(action.entity)
                        is ReactorAction.Opened -> state.transition(IngestLifecycle.OPEN)
                        is ReactorAction.Activated -> state.transition(IngestLifecycle.ACTIVE)
                        is ReactorAction.Draining -> state.transition(IngestLifecycle.DRAINING)
                        is ReactorAction.Closed -> state.transition(IngestLifecycle.CLOSED)
                    }
                }
            } finally {
                // Channel closed — nothing more to do.
            }
        }
    }

    /**
     * Decode [source] in [format] into a [Series] of [LcncEntity].
     *
     * Steps:
     *   1. Materialize [source] to `String` (Paste → identity;
     *      FileStream → UTF-8 decode; Link → `[Link(uri)]`).
     *   2. Dispatch to the appropriate parser.
     *   3. For each parsed entity, [Channel.send] a
     *      [ReactorAction.PublishEntity] over the fanout channel.
     *   4. Return the list as a TrikeShed [Series].
     */
    override suspend fun decode(source: IngestSource, format: IngestFormat): Series<LcncEntity> {
        require(format in supportedFormats) {
            "LcncIngestPipeline does not support $format (supported=$supportedFormats)"
        }

        // CREATED → OPEN → ACTIVE
        openLifecycle()
        activateLifecycle()

        channel.send(ReactorAction.Opened)
        channel.send(ReactorAction.Activated)

        val entities: List<LcncEntity> = withContext(Dispatchers.Default) {
            val text = materialize(source)
            when (format) {
                IngestFormat.CSV -> parseDelimited(text, ',')
                IngestFormat.TSV -> parseDelimited(text, '\t')
                IngestFormat.MARKDOWN -> parseMarkdown(text)
                IngestFormat.HTML -> parseHtml(text)
                IngestFormat.JSON -> parseJson(text)
                IngestFormat.LCNC_NATIVE -> parseLcncNative(text)
            }.also { parsed ->
                // Fan each entity through the channel. trySend because the
                // channel may be at capacity under bursty input; in normal
                // use the consumer loop drains promptly.
                parsed.forEach { entity ->
                    val result = channel.trySend(ReactorAction.PublishEntity(entity))
                    check(result.isSuccess) {
                        "ReactorAction channel closed while emitting ${entity.id}"
                    }
                }
            }
        }

        return entities.size j { i -> entities[i] }
    }

    override suspend fun decodeText(text: String, format: IngestFormat): Series<LcncEntity> {
        return decode(IngestSource.Paste(text), format)
    }

    /** Stop accepting new work; transition ACTIVE → DRAINING. */
    suspend fun drain() {
        if (lifecycleState == IngestLifecycle.ACTIVE) {
            lifecycleState = IngestLifecycle.DRAINING
            channel.send(ReactorAction.Draining)
        }
    }

    /**
     * Close the pipeline. Idempotent. Ensures the lifecycle reaches
     * CLOSED and the fanout scope is cancelled so background coroutines
     * tied to the pipeline exit.
     */
    suspend fun close() {
        if (lifecycleState == IngestLifecycle.ACTIVE) {
            lifecycleState = IngestLifecycle.DRAINING
            channel.trySend(ReactorAction.Draining)
        }
        if (lifecycleState != IngestLifecycle.CLOSED) {
            lifecycleState = IngestLifecycle.CLOSED
            channel.trySend(ReactorAction.Closed)
        }
        channel.close()
        fanoutScope.cancel()
    }

    private fun openLifecycle() {
        if (lifecycleState == IngestLifecycle.CREATED) lifecycleState = IngestLifecycle.OPEN
    }

    private fun activateLifecycle() {
        if (lifecycleState == IngestLifecycle.OPEN) lifecycleState = IngestLifecycle.ACTIVE
    }

    private fun materialize(source: IngestSource): String = when (source) {
        is IngestSource.Paste -> source.content
        is IngestSource.FileStream -> source.data.decodeToString()
        is IngestSource.Link -> source.uri
    }

    // ────────────────────────────── Format parsers ──────────────────────────────

    /**
     * RFC-4180-ish CSV/TSV parser: handles quoted fields with `""` escapes
     * and CRLF/LF row separators. The first non-empty row is treated as
     * the header (column-name origin), and each subsequent row becomes an
     * [LcncPage] (row container) with one [LcncBlock] per cell.
     */
    private fun parseDelimited(text: String, delimiter: Char): List<LcncEntity> {
        val rows: List<List<String>> = parseDelimitedRows(text, delimiter)
        if (rows.isEmpty()) return emptyList()

        val headers: List<String> = rows.first()
        val body: List<List<String>> = rows.drop(1)

        val pages: List<LcncPage> = body.mapIndexed { rowIdx, cells ->
            val blocks: List<LcncBlock> = headers.mapIndexedNotNull { colIdx, header ->
                if (colIdx >= cells.size) null
                else LcncBlock(
                    id = "csv-$rowIdx-$colIdx",
                    type = if (colIdx == 0) "title" else "cell:$header",
                    parentId = "csv-row-$rowIdx",
                    children = null,
                    content = cells[colIdx],
                )
            }
            LcncPage(
                id = "csv-row-$rowIdx",
                title = cells.firstOrNull().orEmpty(),
                parentId = "csv-db",
                contentBlocks = blocks.size j { i -> blocks[i] },
            )
        }

        val database = LcncDatabase(
            id = "csv-db",
            title = "Imported CSV",
            parentId = null,
            pages = pages.size j { i -> pages[i] },
        )

        return listOf(database)
    }

    /** Split delimited text into a list of row vectors, supporting quoted fields. */
    private fun parseDelimitedRows(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val current = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < n && text[i + 1] == '"' -> {
                        field.append('"')
                        i += 2
                    }
                    c == '"' -> {
                        inQuotes = false
                        i++
                    }
                    else -> {
                        field.append(c)
                        i++
                    }
                }
                c == '"' -> {
                    inQuotes = true
                    i++
                }
                c == delimiter -> {
                    current += field.toString()
                    field.setLength(0)
                    i++
                }
                c == '\n' -> {
                    current += field.toString()
                    field.setLength(0)
                    rows += current
                    current.clear()
                    i++
                }
                c == '\r' -> {
                    i++ // swallow CR; LF handles row break
                }
                else -> {
                    field.append(c)
                    i++
                }
            }
        }
        if (field.isNotEmpty() || current.isNotEmpty()) {
            current += field.toString()
            if (current.isNotEmpty()) rows += current
        }
        return rows
    }

    /**
     * Minimal Markdown parser: ATX headings (`#` .. `######`), blank-line
     * paragraphs, `-` / `*` / `1.` list items, and fenced code blocks
     * (` ``` `).
     */
    private fun parseMarkdown(text: String): List<LcncEntity> {
        val blocks = mutableListOf<LcncBlock>()
        var id = 0
        var paraBuf = StringBuilder()
        var listBuf = mutableListOf<String>()

        fun nextId(): String = "md-${id++}"

        fun flushParagraph() {
            if (paraBuf.isNotBlank()) {
                blocks += LcncBlock(
                    id = nextId(),
                    type = "paragraph",
                    parentId = null,
                    content = paraBuf.toString().trim(),
                )
                paraBuf.setLength(0)
            }
        }

        fun flushList() {
            if (listBuf.isNotEmpty()) {
                listBuf.forEach { item ->
                    blocks += LcncBlock(
                        id = nextId(),
                        type = "list_item",
                        parentId = null,
                        content = item,
                    )
                }
                listBuf = mutableListOf()
            }
        }

        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("```") -> {
                    flushParagraph(); flushList()
                    val lang = line.removePrefix("```").trim()
                    val body = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) {
                        body.appendLine(lines[i])
                        i++
                    }
                    blocks += LcncBlock(
                        id = nextId(),
                        type = "code_block",
                        parentId = null,
                        content = mapOf("language" to lang, "source" to body.toString().trimEnd()),
                    )
                    if (i < lines.size) i++ // skip closing fence
                }
                line.startsWith("#") -> {
                    flushParagraph(); flushList()
                    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    val text2 = line.substring(level).trim()
                    blocks += LcncBlock(
                        id = nextId(),
                        type = "heading_$level",
                        parentId = null,
                        content = text2,
                    )
                    i++
                }
                line.isBlank() -> {
                    flushParagraph(); flushList()
                    i++
                }
                line.matches(Regex("^\\s*([-*])\\s+.*")) ||
                    line.matches(Regex("^\\s*\\d+\\.\\s+.*")) -> {
                    flushParagraph()
                    val item = line.trimStart().let {
                        when {
                            it.startsWith("- ") || it.startsWith("* ") ->
                                it.removePrefix("- ").removePrefix("* ")
                            else -> it.replaceFirst(Regex("^\\d+\\.\\s+"), "")
                        }
                    }
                    listBuf += item
                    i++
                }
                else -> {
                    flushList()
                    paraBuf.append(line).append('\n')
                    i++
                }
            }
        }
        flushParagraph(); flushList()

        val page = LcncPage(
            id = "md-page",
            title = "Imported Markdown",
            parentId = null,
            contentBlocks = blocks.size j { idx -> blocks[idx] },
        )
        return listOf(page)
    }

    /**
     * Minimal HTML parser: extracts `<h1>..<h6>`, `<p>`, `<li>`, and
     * `<pre>`/`<code>` blocks. Anything else is silently skipped — the
     * intent here is *LCNC document shape*, not browser fidelity.
     */
    private fun parseHtml(text: String): List<LcncEntity> {
        val blocks = mutableListOf<LcncBlock>()
        var id = 0

        val tagRegex = Regex(
            "(?si)<(h[1-6]|p|li|pre|code)\\b[^>]*>(.*?)</\\1>",
        )
        for (m in tagRegex.findAll(text)) {
            val tag = m.groupValues[1].lowercase()
            val raw = m.groupValues[2]
                .replace(Regex("<[^>]+>"), "")
                .trim()
            if (raw.isEmpty()) continue
            val type = when (tag) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> "heading_${tag[1]}"
                "p" -> "paragraph"
                "li" -> "list_item"
                "pre", "code" -> "code_block"
                else -> "html_${tag}"
            }
            blocks += LcncBlock(
                id = "html-${id++}",
                type = type,
                parentId = null,
                content = raw,
            )
        }

        val page = LcncPage(
            id = "html-page",
            title = "Imported HTML",
            parentId = null,
            contentBlocks = blocks.size j { idx -> blocks[idx] },
        )
        return listOf(page)
    }

    /**
     * Minimal JSON parser. Produces a single [LcncPage] whose blocks each
     * hold a `{ path, value }` snapshot for every scalar leaf in the
     * input. Sufficient for first-class ingest; deep parsing of arbitrary
     * JSON is delegated to dedicated reducers downstream.
     */
    private fun parseJson(text: String): List<LcncEntity> {
        val parsed: Any? = jsonParse(text.trim())
        val blocks = mutableListOf<LcncBlock>()
        var id = 0
        flattenJson("$", parsed).forEach { (path, value) ->
            blocks += LcncBlock(
                id = "json-${id++}",
                type = "json_value",
                parentId = null,
                content = mapOf("path" to path, "value" to value),
            )
        }
        val page = LcncPage(
            id = "json-page",
            title = "Imported JSON",
            parentId = null,
            contentBlocks = blocks.size j { idx -> blocks[idx] },
        )
        return listOf(page)
    }

    /** LCNC native: pages delimited by lines beginning with `--- ` treated as titles. */
    private fun parseLcncNative(text: String): List<LcncEntity> {
        val pages = mutableListOf<LcncPage>()
        var currentTitle: String? = null
        val currentBlocks = mutableListOf<LcncBlock>()
        var id = 0
        var pageIdx = 0

        fun flush() {
            val title = currentTitle ?: return
            pages += LcncPage(
                id = "lcnc-page-$pageIdx",
                title = title,
                parentId = null,
                contentBlocks = currentBlocks.size j { i -> currentBlocks[i] },
            )
            currentBlocks.clear()
            pageIdx++
        }

        text.lineSequence().forEach { line ->
            when {
                line.startsWith("--- ") -> {
                    flush()
                    currentTitle = line.removePrefix("--- ").trim()
                }
                line.isBlank() -> Unit
                else -> currentBlocks += LcncBlock(
                    id = "lcnc-${id++}",
                    type = "paragraph",
                    parentId = null,
                    content = line,
                )
            }
        }
        flush()

        return if (pages.isEmpty()) {
            listOf(
                LcncPage(
                    id = "lcnc-page-0",
                    title = "Untitled",
                    parentId = null,
                    contentBlocks = 0 j { _: Int -> error("empty series") },
                )
            )
        } else pages
    }

    // ──────────────── JSON parsing (recursive descent, commonMain-safe) ────────────────

    private fun jsonParse(input: String): Any? = jsonReadValue(input, 0).first

    private fun jsonReadValue(s: String, i: Int): Pair<Any?, Int> {
        val idx = skipWsIndex(s, i)
        if (idx >= s.length) return null to idx
        return when (val c = s[idx]) {
            '{' -> jsonReadObject(s, idx)
            '[' -> jsonReadArray(s, idx)
            '"' -> jsonReadString(s, idx)
            't', 'f' -> jsonReadBool(s, idx)
            'n' -> jsonReadNull(s, idx)
            else -> if (c == '-' || c.isDigit()) jsonReadNumber(s, idx)
            else error("Unexpected JSON character '$c' at $idx")
        }
    }

    private fun jsonReadObject(s: String, i: Int): Pair<Map<String, Any?>, Int> {
        val start = skipWsIndex(s, i + 1)
        val out = linkedMapOf<String, Any?>()
        if (start < s.length && s[start] == '}') return out to (start + 1)
        var idx = start
        while (idx < s.length) {
            idx = skipWsIndex(s, idx)
            val (k, afterKey) = jsonReadString(s, idx)
            idx = skipWsIndex(s, afterKey)
            require(s[idx] == ':') { "Expected ':' at $idx" }
            idx = skipWsIndex(s, idx + 1)
            val (v, afterVal) = jsonReadValue(s, idx)
            out[k] = v
            idx = skipWsIndex(s, afterVal)
            when (s[idx]) {
                ',' -> { idx++; continue }
                '}' -> return out to (idx + 1)
                else -> error("Expected ',' or '}' at $idx")
            }
        }
        error("Unterminated JSON object")
    }

    private fun jsonReadArray(s: String, i: Int): Pair<List<Any?>, Int> {
        val start = skipWsIndex(s, i + 1)
        val out = mutableListOf<Any?>()
        if (start < s.length && s[start] == ']') return out to (start + 1)
        var idx = start
        while (idx < s.length) {
            idx = skipWsIndex(s, idx)
            val (v, afterVal) = jsonReadValue(s, idx)
            out += v
            idx = skipWsIndex(s, afterVal)
            when (s[idx]) {
                ',' -> { idx++; continue }
                ']' -> return out to (idx + 1)
                else -> error("Expected ',' or ']' at $idx")
            }
        }
        error("Unterminated JSON array")
    }

    private fun jsonReadString(s: String, i: Int): Pair<String, Int> {
        require(s[i] == '"') { "Expected '\"' at $i" }
        val sb = StringBuilder()
        var idx = i + 1
        while (idx < s.length) {
            val c = s[idx]
            when {
                c == '"' -> return sb.toString() to (idx + 1)
                c == '\\' -> {
                    val esc = s[idx + 1]
                    when (esc) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append(' ')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = s.substring(idx + 2, idx + 6)
                            sb.append(hex.toInt(16).toChar())
                            idx += 4
                        }
                        else -> sb.append(esc)
                    }
                    idx += 2
                }
                else -> { sb.append(c); idx++ }
            }
        }
        error("Unterminated JSON string at $i")
    }

    private fun jsonReadBool(s: String, i: Int): Pair<Boolean, Int> = when {
        s.startsWith("true", i) -> true to (i + 4)
        s.startsWith("false", i) -> false to (i + 5)
        else -> error("Expected boolean at $i")
    }

    private fun jsonReadNull(s: String, i: Int): Pair<Any?, Int> {
        require(s.startsWith("null", i)) { "Expected null at $i" }
        return null to (i + 4)
    }

    private fun jsonReadNumber(s: String, i: Int): Pair<Any, Int> {
        var idx = i
        if (s[idx] == '-') idx++
        while (idx < s.length && (s[idx].isDigit() || s[idx] in ".eE+-")) idx++
        val raw = s.substring(i, idx)
        return when {
            raw.contains('.') || raw.contains('e') || raw.contains('E') ->
                raw.toDouble() to idx
            else -> raw.toLong() to idx
        }
    }

    private fun skipWsIndex(s: String, i: Int): Int {
        var idx = i
        while (idx < s.length && s[idx].isWhitespace()) idx++
        return idx
    }

    private fun flattenJson(
        path: String,
        value: Any?,
        out: MutableList<Pair<String, Any?>> = mutableListOf(),
    ): List<Pair<String, Any?>> {
        when (value) {
            is Map<*, *> -> value.forEach { (k, v) ->
                val sub = "$path.${k.toString().escapeJsonPath()}"
                if (v is Map<*, *> || v is List<*>) flattenJson(sub, v, out)
                else out += sub to v
            }
            is List<*> -> value.forEachIndexed { i, v ->
                val sub = "$path[$i]"
                if (v is Map<*, *> || v is List<*>) flattenJson(sub, v, out)
                else out += sub to v
            }
            else -> out += path to value
        }
        return out
    }

    private fun String.escapeJsonPath(): String =
        replace(".", "\\.")
}
