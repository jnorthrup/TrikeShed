package borg.trikeshed.narsese

import java.io.File

/**
 * Append-only ledgers on the forge filesystem for what NARS has been taught.
 *
 * The daemon already wrote taught axioms to the `kif-ledger/` couch plane and admitted rules to
 * `rete-rule/`, and the boot thaw re-read them — but nothing survived a restart, and measuring
 * why turned up a deeper gap than the one being fixed: [borg.trikeshed.couch.CouchStoreFactory.casBacked]
 * constructs a FRESH `CouchHeadProjection` at every boot. Document BODIES are durable (canonical
 * CBOR in CAS), but the id→revision index that makes them findable is in-memory only. So
 * `allDocs("kif-ledger/")` can only ever see documents written since this boot, and the thaw was
 * restoring nothing but what the same boot had just re-seeded. Measured on the live daemon:
 * teach took the plane 3 → 5 docs, and after a restart it read 3 again.
 *
 * Repairing the couch index is a much larger change and is not attempted here. These files are
 * the durable plane instead — the forge home is a real filesystem, and one line per fact is
 * enough for a restore. The couch writes stay: they are useful within a session and for
 * replication. This is what is actually re-read at boot.
 *
 * Lines are JSON objects, one per line, appended and never rewritten — a corrupt tail costs its
 * own line and nothing before it. Replay is idempotent because `assertKif` and
 * `CausalityReteElement.admit` are both set-unions.
 */
object NarsDurableLedger {

    /** Where the ledgers live under a forge home. */
    fun dir(forgeHome: File): File = File(forgeHome, "nars")

    fun axiomFile(forgeHome: File): File = File(dir(forgeHome), "kif-ledger.jsonl")
    fun ruleFile(forgeHome: File): File = File(dir(forgeHome), "rete-rules.jsonl")

    private fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    private fun unesc(s: String): String {
        val out = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                i++
                when (val e = s[i]) {
                    'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                    '"' -> out.append('"'); '\\' -> out.append('\\')
                    'u' -> { out.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> out.append(e)
                }
            } else out.append(c)
            i++
        }
        return out.toString()
    }

    /** Read one string field out of a flat JSON object line. */
    private fun field(line: String, name: String): String? {
        val key = "\"$name\":\""
        val at = line.indexOf(key)
        if (at < 0) return null
        val start = at + key.length
        var i = start
        while (i < line.length) {
            if (line[i] == '\\') { i += 2; continue }
            if (line[i] == '"') break
            i++
        }
        if (i > line.length) return null
        return unesc(line.substring(start, i))
    }

    private fun append(file: File, line: String) {
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
    }

    /** Record one taught axiom in canonical KIF text. */
    fun appendAxiom(forgeHome: File, kif: String) =
        append(axiomFile(forgeHome), """{"kif":"${esc(kif)}"}""")

    /** Record one admitted rule, by the fields `ruleCid` hashes, so identity survives. */
    fun appendRule(forgeHome: File, rule: EternalRule) = append(
        ruleFile(forgeHome),
        """{"antecedent":"${esc(rule.antecedent)}","consequent":"${esc(rule.consequent)}",""" +
            """"copula":"${rule.copula.name}","evidence":"${rule.evidence.packed}",""" +
            """"provenanceCid":"${esc(rule.provenanceCid ?: "")}"}""",
    )

    /** Distinct taught axioms, oldest first. A line that will not parse is skipped, not fatal. */
    fun readAxioms(forgeHome: File): List<String> {
        val f = axiomFile(forgeHome)
        if (!f.isFile) return emptyList()
        val seen = LinkedHashSet<String>()
        f.forEachLine { line -> field(line, "kif")?.takeIf { it.isNotBlank() }?.let { seen.add(it) } }
        return seen.toList()
    }

    /** Distinct admitted rules, oldest first, reconstructed with their original `ruleCid`. */
    fun readRules(forgeHome: File): List<EternalRule> {
        val f = ruleFile(forgeHome)
        if (!f.isFile) return emptyList()
        val out = LinkedHashMap<String, EternalRule>()
        f.forEachLine { line ->
            val antecedent = field(line, "antecedent") ?: return@forEachLine
            val consequent = field(line, "consequent") ?: return@forEachLine
            val packed = field(line, "evidence")?.toLongOrNull() ?: return@forEachLine
            val copula = runCatching { NalCopula.valueOf(field(line, "copula") ?: "") }.getOrNull()
                ?: return@forEachLine
            val rule = EternalRule(
                antecedent = antecedent,
                consequent = consequent,
                copula = copula,
                evidence = EvidenceCoord(packed),
                provenanceCid = field(line, "provenanceCid")?.takeIf { it.isNotBlank() },
            )
            out[rule.ruleCid.value] = rule
        }
        return out.values.toList()
    }
}
