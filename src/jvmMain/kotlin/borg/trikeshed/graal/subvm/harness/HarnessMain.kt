package borg.trikeshed.graal.subvm.harness

import java.io.File

/**
 * Runs every [Capabilities] probe on the current host and writes the row as JSON (one file per host)
 * plus a Markdown matrix merged over every host file found in the output directory.
 *
 *   java -cp … borg.trikeshed.graal.subvm.harness.HarnessMain <outDir> [hostName]
 *   ./subvm-harness <outDir> [hostName]            (native-image build of this class)
 *
 * Exit code: 0 if no probe FAILED (ABSENT and BOUNDED are measurements, not failures), 1 otherwise.
 */
object HarnessMain {
    data class Row(val host: Host, val cells: List<Cell>)

    fun measure(hostName: String? = null, out: (String) -> Unit = { println(it) }): Row {
        val host = Host.current(hostName)
        out("── subvm harness on $host")
        val cells = Capabilities.all.map { p ->
            val t0 = System.nanoTime()
            val (v, e) = try { p.run() } catch (t: Throwable) { Verdict.FAILED to "${t::class.simpleName}: ${t.message?.lineSequence()?.firstOrNull()}" }
            val c = Cell(p.id, v, e, (System.nanoTime() - t0) / 1000)
            out("  ${c.verdict.name.padEnd(7)} ${c.probe.padEnd(24)} ${c.micros / 1000}ms  ${c.evidence.take(110)}")
            c
        }
        return Row(host, cells)
    }

    fun toJson(r: Row): String = buildString {
        append("{\"host\":{\"name\":\"${r.host.name}\",\"os\":\"${r.host.os}\",\"arch\":\"${r.host.arch}\",\"runtime\":\"${r.host.runtime}\",\"vm\":\"${esc(r.host.vmName)}\",\"nativeImage\":${r.host.nativeImage}},\"cells\":[")
        r.cells.forEachIndexed { i, c -> if (i > 0) append(','); append("{\"probe\":\"${c.probe}\",\"verdict\":\"${c.verdict}\",\"micros\":${c.micros},\"evidence\":\"${esc(c.evidence)}\"}") }
        append("]}")
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    /** Minimal reader for the files this writer produces (no JSON library on the native-image path). */
    fun fromJson(s: String): Row {
        fun field(obj: String, k: String): String = Regex("\"$k\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(obj)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\n", "\n")?.replace("\\\\", "\\") ?: ""
        val hostObj = s.substringAfter("\"host\":").substringBefore("},\"cells\"") + "}"
        val host = Host(field(hostObj, "name"), field(hostObj, "os"), field(hostObj, "arch"), field(hostObj, "runtime"), field(hostObj, "vm"), hostObj.contains("\"nativeImage\":true"))
        val cells = Regex("\\{\"probe\":\"([^\"]+)\",\"verdict\":\"([A-Z]+)\",\"micros\":(\\d+),\"evidence\":\"((?:[^\"\\\\]|\\\\.)*)\"\\}").findAll(s)
            .map { m -> Cell(m.groupValues[1], Verdict.valueOf(m.groupValues[2]), m.groupValues[4].replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\"), m.groupValues[3].toLong()) }.toList()
        return Row(host, cells)
    }

    fun matrix(rows: List<Row>): String = buildString {
        val hosts = rows.sortedBy { it.host.name }
        val probes = Capabilities.all.map { it.id }
        appendLine("# Sub-VM capability matrix")
        appendLine()
        appendLine("Measured, not declared: every cell is a probe result on a real host. `✓` OK · `◐` BOUNDED (works within a documented bound) · `✗` FAILED · `—` ABSENT (not available on that host, reason in the host section).")
        appendLine()
        append("| capability |"); hosts.forEach { append(" ${it.host.name} |") }; appendLine()
        append("|---|"); hosts.forEach { append("---|") }; appendLine()
        for (p in probes) {
            append("| `$p` |")
            for (h in hosts) {
                val c = h.cells.find { it.probe == p }
                append(" " + when (c?.verdict) { Verdict.OK -> "✓"; Verdict.BOUNDED -> "◐"; Verdict.FAILED -> "✗"; Verdict.ABSENT -> "—"; null -> "·" } + " |")
            }
            appendLine()
        }
        for (h in hosts) {
            appendLine(); appendLine("## ${h.host}"); appendLine()
            appendLine("| probe | verdict | ms | evidence |"); appendLine("|---|---|---|---|")
            for (c in h.cells) appendLine("| `${c.probe}` | ${c.verdict} | ${c.micros / 1000} | ${c.evidence.replace("|", "\\|").replace("\n", " ")} |")
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val outDir = File(args.getOrElse(0) { "docs/subvm" }).apply { mkdirs() }
        val row = measure(args.getOrNull(1))
        File(outDir, "capabilities-${row.host.name}.json").writeText(toJson(row))
        val rows = outDir.listFiles { f -> f.name.startsWith("capabilities-") && f.name.endsWith(".json") }!!.map { fromJson(it.readText()) }
        File(outDir, "capability-matrix.md").writeText(matrix(rows))
        val failed = row.cells.count { it.verdict == Verdict.FAILED }
        println("── ${row.cells.size} probes: ${row.cells.count { it.verdict == Verdict.OK }} ok, ${row.cells.count { it.verdict == Verdict.BOUNDED }} bounded, ${row.cells.count { it.verdict == Verdict.ABSENT }} absent, $failed failed → ${File(outDir, "capability-matrix.md").path}")
        if (failed > 0) System.exit(1)
    }
}
