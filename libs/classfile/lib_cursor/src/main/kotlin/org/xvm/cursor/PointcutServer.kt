package org.xvm.cursor

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeries
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.Reducer
import borg.trikeshed.lib.ReduxMutableSeries
import borg.trikeshed.lib.ChunkedMutableSeries
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.lang.System.nanoTime
import java.time.Instant
import kotlin.system.exitProcess

/**
 * PointcutServer — consumes [VmPointcutEmitter.py][scripts/VmPointcutEmitter.py]
 * NDJSON output via stdin, maps opcodes into [VmPointcutKind], and fires them
 * to any registered handler.
 *
 * Pipeline:
 *   analyzeHeadless
 *     → VmPointcutEmitter.py (emits NDJSON to stdout)
 *     → stdin pipe
 *     → this server (parse + index)
 *     → either a [Flow][kotlinx.coroutines.flow.Flow] for coroutine callers,
 *        or direct handler callbacks for plain Java/Kotlin callers.
 *
 * Usage (daemon mode):
 *   analyzeHeadless ... -postScript VmPointcutEmitter.py --scriptPath lib_cursor/scripts - \\\
 *     | java -cp lib_cursor.jar org.xvm.cursor.PointcutServerKt
 *
 * Usage (library mode — call [stdinFlow] from coroutine scope):
 *   scope.launch { PointcutServer.stdinFlow().collect { event -> ... } }
 *
 * Usage (plain threading — no coroutines needed):
 *   PointcutServer.subscribe { event -> ... }
 *   // blocks — call from a Thread
 *   PointcutServer.blockingStdIn()
 */
data class CruduxAction(val type: String, val payload: Map<*, *>)

data class PointcutState(
    val activePointcuts: Map<Int, String> = emptyMap()
)

object PointcutReducer : Reducer<CruduxAction, PointcutState> {
    override val zero = PointcutState()

    override fun combine(acc: PointcutState, element: CruduxAction): PointcutState {
        return when (element.type) {
            "CREATE", "UPDATE" -> {
                val opcode = (element.payload["opcode"] as? Number)?.toInt() ?: return acc
                val method = element.payload["method"] as? String ?: return acc
                acc.copy(activePointcuts = acc.activePointcuts + (opcode to method))
            }
            "DELETE" -> {
                val opcode = (element.payload["opcode"] as? Number)?.toInt() ?: return acc
                acc.copy(activePointcuts = acc.activePointcuts - opcode)
            }
            else -> acc
        }
    }
}

object PointcutServer {

    init {
        PointcutWireSpine.bootstrapOnLaunch()
    }

    private val seq = AtomicInteger(0)
    private val version = AtomicInteger(0)

    private val frontLine = ChunkedMutableSeries<CruduxAction>(chunkSize = 100)

    private val _journal: ReduxMutableSeries<CruduxAction, PointcutState> =
        makeReduxJournal()

    @Suppress("UNCHECKED_CAST")
    private fun makeReduxJournal():
        ReduxMutableSeries<CruduxAction, PointcutState> {
        val delegate = ChunkedMutableSeries<CruduxAction>(chunkSize = 100) as borg.trikeshed.lib.MutableSeries<CruduxAction>
        val reducer = PointcutReducer
        val init = PointcutState()
        val cap = CruduxAction("", emptyMap<String, Any>())
        // Use explicit constructor with no-name parameter order
        return ReduxMutableSeries(delegate, reducer, init, cap)
    }

    val pointcutJournal: ReduxMutableSeries<CruduxAction, PointcutState>
        get() = _journal

    /** Active subscribers */
    private val subs = ConcurrentHashMap<Int, (PointcutEvent) -> Unit>()

    /**
     * Pointcut event record — mirrors [VmPointcutPublisher.PointcutEvent] but
     * carries the raw pointcut payload for downstream consumers.
     */
    data class PointcutEvent(
        val seq: Int,
        val nano: Long,
        val opcode: VmPointcutKind,
        val method: String,
        val addr: Int = 0,
        val pcodeCount: Int = 0,
        val pcodeOps: Series<String> = emptySeries(),
        val calls: Series<String> = emptySeries(),
        val branches: Series<String> = emptySeries(),
    )

    /**
     * Parse one NDJSON line into [PointcutEvent].
     * Returns null for blank lines and control messages (DONE, ERROR).
     */
    fun parseLine(line: String): PointcutEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        val raw = try {
            parseMap(trimmed)
        } catch (e: Exception) {
            System.err.println("[PointcutServer] JSON parse error: ${e.message}")
            return null
        }

        // Control messages
        when (raw["type"]) {
            "DONE" -> return null
            "ERROR" -> {
                System.err.println("[PointcutServer] Error: ${raw["message"] ?: raw}")
                return null
            }
            "CRUDUX" -> {
                notifyCrudux(raw)
                return null
            }
        }

        val opcode = (raw["opcode"] as? Number)?.toInt() ?: return null
        val name = raw["name"] as? String ?: ""
        val method = raw["method"] as? String ?: ""
        val pcodeCount = (raw["pcode_count"] as? Number)?.toInt() ?: 0
        @Suppress("UNCHECKED_CAST")
        val pcodeOps = ((raw["pcode_ops"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()).toSeries()
        @Suppress("UNCHECKED_CAST")
        val calls = ((raw["calls"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()).toSeries()
        @Suppress("UNCHECKED_CAST")
        val branches = ((raw["branches"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()).toSeries()

        return PointcutEvent(
            seq = seq.getAndIncrement(),
            nano = nanoTime(),
            opcode = VmPointcutKind.fromOpcode(opcode),
            method = method,
            addr = opcode,
            pcodeCount = pcodeCount,
            pcodeOps = pcodeOps,
            calls = calls,
            branches = branches,
        )
    }

    /**
     * Minimal hand-rolled JSON object parser.
     * Handles: quoted strings, numbers, booleans, null, arrays of primitives.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseMap(s: String): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        val content = s.removePrefix("{").removeSuffix("}").trim()
        var i = 0
        while (i < content.length) {
            i = skipWhitespace(content, i)
            if (i >= content.length) break
            // key
            var key: String? = null
            if (content[i] == '"') {
                val (k, ni) = parseString(content, i)
                key = k
                i = ni
            }
            i = skipWhitespace(content, i)
            if (i >= content.length || content[i] != ':') {
                i++ // advance past garbage
                continue
            }
            i++ // skip ':'
            i = skipWhitespace(content, i)
            if (i >= content.length) break
            val (value, ni) = parseValue(content, i)
            if (key != null) result[key] = value
            i = skipWhitespace(content, ni)
            if (i < content.length && content[i] == ',') i++
        }
        return result
    }

    private fun skipWhitespace(s: String, i: Int): Int {
        var j = i
        while (j < s.length && s[j] in " \t\n\r") j++
        return j
    }

    private fun parseString(s: String, i: Int): Pair<String, Int> {
        // s[i] == '"'
        val sb = StringBuilder()
        var j = i + 1
        while (j < s.length) {
            val c = s[j]
            if (c == '\\') {
                j++
                if (j < s.length) sb.append(s[j])
            } else if (c == '"') {
                return sb.toString() to (j + 1)
            } else {
                sb.append(c)
            }
            j++
        }
        return sb.toString() to j
    }

    private fun parseValue(s: String, i: Int): Pair<Any?, Int> {
        val si = skipWhitespace(s, i)
        if (si >= s.length) return null to si
        val c = s[si]
        return when {
            c == '"' -> {
                val (str, ni) = parseString(s, si)
                str to ni
            }
            c == '[' -> parseArray(s, si)
            c == '{' -> parseMap(s.substring(si)) to s.length
            c == 't' && s.startsWith("true", si) -> true to (si + 4)
            c == 'f' && s.startsWith("false", si) -> false to (si + 5)
            c == 'n' && s.startsWith("null", si) -> null to (si + 4)
            c == '-' || c.isDigit() -> parseNumber(s, si)
            else -> null to (si + 1)
        }
    }

    private fun parseNumber(s: String, i: Int): Pair<Number, Int> {
        var j = i
        if (j < s.length && s[j] == '-') j++
        while (j < s.length && s[j].isDigit()) j++
        if (j < s.length && s[j] == '.') {
            j++
            while (j < s.length && s[j].isDigit()) j++
        }
        if (j < s.length && s[j] in "eE") {
            j++
            if (j < s.length && s[j] in "+-") j++
            while (j < s.length && s[j].isDigit()) j++
        }
        val token = s.substring(i, j)
        return try {
            if ('.' in token || 'e' in token.lowercase()) token.toDouble() else token.toInt()
        } catch (e: NumberFormatException) {
            token.toDouble() as Number
        } to j
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseArray(s: String, i: Int): Pair<List<Any?>, Int> {
        // s[i] == '['
        val result = mutableListOf<Any?>()
        var j = s.indexOf('[', i) + 1
        val end = s.indexOf(']', j).coerceAtLeast(j)
        val content = s.substring(j, end).trim()
        if (content.isNotEmpty()) {
            var k = 0
            while (k < content.length) {
                k = skipWhitespace(content, k)
                if (k >= content.length) break
                val (v, nk) = parseValue(content, k)
                result.add(v)
                k = skipWhitespace(content, nk)
                if (k < content.length && content[k] == ',') k++
            }
        }
        return result to (end + 1)
    }

    /**
     * Subscribe a handler. Handler is invoked on the calling thread of [blockingStdIn].
     * @return subscription id for [unsubscribe]
     */
    fun subscribe(fn: (PointcutEvent) -> Unit): Int {
        val id = seq.getAndIncrement()
        subs[id] = fn
        return id
    }

    /** Unsubscribe by id */
    fun unsubscribe(id: Int) = subs.remove(id)

    private fun notify(e: PointcutEvent) {
        for (fn in subs.values) {
            try {
                fn(e)
            } catch (ex: Exception) {
                System.err.println("[PointcutServer] handler error: ${ex.message}")
            }
        }
    }

    private fun notifyCrudux(raw: Map<String, Any?>) {
        val action = raw["action"] as? String ?: return
        val payload = raw["payload"] as? Map<*, *> ?: return
        
        pointcutJournal.dispatch(CruduxAction(action, payload))
        
        when (action) {
            "CREATE" -> {
                val opcode = (payload["opcode"] as? Number)?.toInt() ?: return
                val method = payload["method"] as? String ?: return
                VmPointcutPublisher.publish(opcode, method, opcode)
            }
        }
    }

    /**
     * Block-reading stdin loop — drives the event loop from a plain [Thread].
     * Returns when EOF is reached or a "DONE" control message is received.
     *
     * @param verbose print each event to stderr
     * @return total event count parsed
     */
    fun blockingStdIn(verbose: Boolean = false): Int {
        val reader = BufferedReader(InputStreamReader(System.`in`))
        var count = 0
        var done = false

        while (!done) {
            val line = reader.readLine() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val event = parseLine(trimmed)
            if (event == null) {
                // Check if DONE
                if (trimmed.contains("\"type\"") && trimmed.contains("DONE")) done = true
                continue
            }

            count++
            if (verbose) {
                System.err.printf(
                    "[PointcutServer] #%-6d 0x%02X %-15s %s%n",
                    count, event.opcode.opcode, event.opcode.name, event.method
                )
            }
            notify(event)
        }

        return count
    }

    /** Print a readable summary table of all events from stdin */
    fun dumpAll() {
        val reader = BufferedReader(InputStreamReader(System.`in`))
        val out = StringBuilder()
        out.appendLine(" opcode  name               method                          pcode#  calls  branches")
        out.appendLine("───────  ─────────────────  ────────────────────────────────  ─────  ─────  ─────────")

        var line: String?
        var count = 0
        while (reader.readLine().also { line = it } != null) {
            val event = parseLine(line!!) ?: continue
            count++
            out.appendLine(
                " 0x%02X   %-17s %-30s %6d %6d %8d".format(
                    event.opcode.opcode,
                    event.opcode.name,
                    event.method.take(29).padEnd(29),
                    event.pcodeCount,
                    event.calls.size,
                    event.branches.size,
                )
            )
        }
        System.err.println("[PointcutServer] Parsed $count opcode entries")
        print(out.toString())
    }
}


// ── Standalone entry point ───────────────────────────────────────────────────
fun main(args: Array<String>) {
    val verbose = args.contains("-v") || args.contains("--verbose")
    val dump = args.contains("-d") || args.contains("--dump")

    System.err.println("[PointcutServer] Started at ${Instant.now()}")
    System.err.flush()

    if (dump) {
        System.err.println("[PointcutServer] Dump mode — reading stdin, printing summary table")
        PointcutServer.dumpAll()
        exitProcess(0)
    }

    System.err.println("[PointcutServer] Daemon mode — reading NDJSON from stdin")

    val count = PointcutServer.blockingStdIn(verbose = verbose)

    System.err.println("[PointcutServer] EOF received. Events parsed: $count")
    exitProcess(0)
}
