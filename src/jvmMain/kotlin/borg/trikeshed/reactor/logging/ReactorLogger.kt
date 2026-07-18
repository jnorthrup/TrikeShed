package borg.trikeshed.reactor.logging

import org.slf4j.Logger
import borg.trikeshed.job.JobLog
import borg.trikeshed.couch.isam.Stringpool
import borg.trikeshed.couch.isam.DurableAppendLog

/**
 * CAS-based WAL Logger that implements the SLF4J facade.
 * This strips bloat by *not* rendering the log strings.
 * Instead, it CAS-memoizes the log template via Stringpool,
 * leaving the parameters as lazy values to be rendered later.
 * It also asserts epoch time UTC.
 */
class ReactorLogger(
    private val name: String,
    private val stringpool: Stringpool,
    private val wal: JobLog,
    private val durableAppendLog: DurableAppendLog? = null
) : Logger {

    override fun getName(): String = name

    // Monotonic sequence source
    private var currentSequence = 0L

    private fun logCas(level: Byte, template: String, vararg args: Any) {
        // 1. Memoize template string using Stringpool (returns offset/CAS)
        val templateCas = stringpool.put(template)

        // 2. Capture Epoch Time UTC (asserted)
        val epochTime = System.currentTimeMillis()

        // 3. Encode to lazy payload:
        //    [level:1][templateCas:4][timestamp:8][args count:4][args...]
        val out = mutableListOf<Byte>()
        out.add(level)

        for (i in 3 downTo 0) out.add((templateCas ushr (i * 8)).toByte())
        for (i in 7 downTo 0) out.add((epochTime ushr (i * 8)).toByte())

        val argCount = args.size
        for (i in 3 downTo 0) out.add((argCount ushr (i * 8)).toByte())

        for (arg in args) {
            val argBytes = arg.toString().encodeToByteArray()
            for (i in 3 downTo 0) out.add((argBytes.size ushr (i * 8)).toByte())
            out.addAll(argBytes.toList())
        }

        // Use a strictly monotonic sequence
        val sequence = ++currentSequence
        val payload = out.toByteArray()

        // Append to job log
        wal.append(sequence, payload)

        // Write to durable append log and cross durability boundary
        durableAppendLog?.append(sequence, payload)
        durableAppendLog?.flush()
    }

    override fun info(s: String) { logCas(1, s) }
    override fun debug(s: String) { logCas(2, s) }
    override fun trace(s: String) { logCas(3, s) }
    override fun warn(s: String) { logCas(4, s) }
    override fun error(s: String) { logCas(5, s) }

    override fun info(s: String, o: Any) { logCas(1, s, o) }
    override fun debug(s: String, o: Any) { logCas(2, s, o) }
    override fun trace(s: String, o: Any) { logCas(3, s, o) }
    override fun warn(s: String, o: Any) { logCas(4, s, o) }
    override fun error(s: String, o: Any) { logCas(5, s, o) }

    override fun info(s: String, o: Any, o2: Any) { logCas(1, s, o, o2) }
    override fun debug(s: String, o: Any, o2: Any) { logCas(2, s, o, o2) }
    override fun trace(s: String, o: Any, o2: Any) { logCas(3, s, o, o2) }
    override fun warn(s: String, o: Any, o2: Any) { logCas(4, s, o, o2) }
    override fun error(s: String, o: Any, o2: Any) { logCas(5, s, o, o2) }

    override fun info(s: String, vararg o: Any) { logCas(1, s, *o) }
    override fun debug(s: String, vararg o: Any) { logCas(2, s, *o) }
    override fun trace(s: String, vararg o: Any) { logCas(3, s, *o) }
    override fun warn(s: String, vararg o: Any) { logCas(4, s, *o) }
    override fun error(s: String, vararg o: Any) { logCas(5, s, *o) }

    override fun isInfoEnabled(): Boolean = true
    override fun isDebugEnabled(): Boolean = true
    override fun isTraceEnabled(): Boolean = true
    override fun isWarnEnabled(): Boolean = true
    override fun isErrorEnabled(): Boolean = true

}
