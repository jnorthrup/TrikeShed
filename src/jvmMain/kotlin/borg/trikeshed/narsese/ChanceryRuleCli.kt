package borg.trikeshed.narsese

import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.lib.packInts
import java.io.File

/**
 * Gibson's *Suits in Chancery* (1907, archive.org `cu31924084259872` djvu text) → normative rules.
 *
 * The OCR text is cut into `§ N.` sections; body lines are kept and the narrower footnote column
 * dropped, hyphenation is joined and footnote markers stripped. Each section in [from]..[to] is
 * parsed by CoreNLP and [ChanceryRules] reads its rules. Every rule is evidence for
 * ⟨subject ⇒ predicate⟩ in an [EvidenceLedger] keyed by (subject term, predicate term), sourced by
 * section, affirmative positive and negated negative; so a principle restated across sections
 * gains confidence and a contradicted one loses frequency.
 *
 * Usage: ChanceryRuleCli <gibson.txt> <fromSection> <toSection> <out.tsv>
 */
object ChanceryRuleCli {
    private val heading = Regex("""^§\s+(\d{1,4})\.\s+(.*)$""")

    /**
     * Section number → cleaned body text. A body heading is a `§ N.` line at full measure or carrying
     * the `—` run-in; the short `§ N.` lines of a chapter's table of contents are neither. The first
     * body heading for a number wins.
     */
    fun sections(raw: String): Map<Int, String> {
        val out = LinkedHashMap<Int, StringBuilder>()
        var cur: StringBuilder? = null
        for (line in raw.lines()) {
            val flat = line.replace(Regex(" {2,}"), " ").trim()
            val h = heading.find(flat)
            if (h != null && (line.length >= 60 || '—' in flat)) {
                val n = h.groupValues[1].toInt()
                cur = if (n in out) null else StringBuilder(h.groupValues[2].replace('—', '.')).append(' ').also { out[n] = it }
                continue
            }
            // Body lines run the full measure; the footnote column is about half as wide.
            if (cur != null && line.length >= 70) cur.append(flat).append('\n')
        }
        return out.mapValues { (_, b) ->
            b.toString()
                .replace(Regex("""(\w)- ?\n(\w)"""), "$1$2")
                .replace('\n', ' ')
                .replace(Regex("""(?<=[a-z,;:.)])\d{1,4}(?=[\s,;.—])"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val secs = sections(File(args[0]).readText()).filterKeys { it in args[1].toInt()..args[2].toInt() }
        println("[gibson] ${secs.size} sections §${args[1]}–§${args[2]}, ${secs.values.sumOf { it.length }} chars")
        val terms = ArrayList<String>(); val ids = HashMap<String, Int>()
        fun term(t: String) = ids.getOrPut(t) { terms.size.also { terms.add(t) } }
        val ledger = EvidenceLedger()
        val rows = ArrayList<String>()
        val t0 = System.nanoTime()
        CoreNlpRuntime().use { nlp ->
            for ((n, body) in secs) {
                val rules = ChanceryRules.extract(nlp.analyze(body))
                for (r in rules) {
                    val e = if (r.affirmative) EvidenceCoord(Nal.UNIT, 0) else EvidenceCoord(0, Nal.UNIT)
                    ledger.observe(packInts(term(r.subject), term(r.predicate)), n, e)
                    rows.add(listOf("§$n", r.subject, r.modal, if (r.affirmative) "+" else "-", r.verb, r.obj ?: "", r.oblique ?: "", r.sentence.take(240)).joinToString("\t"))
                }
            }
        }
        File(args[3]).writeText("section\tsubject\tmodal\tpolarity\tverb\tobject\toblique\tsentence\n" + rows.joinToString("\n") + "\n")
        println("[gibson] ${rows.size} rules in ${(System.nanoTime() - t0) / 1_000_000}ms → ${args[3]}")

        val bySubject = rows.groupingBy { it.split('\t')[1] }.eachCount().entries.sortedByDescending { it.value }.take(10)
        println("[gibson] subjects: " + bySubject.joinToString { "${it.key}=${it.value}" })
        val eternal = ledger.eternal(0.6f)
        println("[nal] ${ledger.size} beliefs; ${eternal.size} restated to c>=0.6:")
        for (s in eternal) {
            val k = ledger.key(s); val t = Nal.truthOf(ledger.evidence(s))
            println("[nal]   ${terms[(k ushr 32).toInt()]} ⇒ ${terms[k.toInt()]}  f=${"%.2f".format(t.frequency)} c=${"%.2f".format(t.confidence)}  §${ledger.basis(s).toIntArray().joinToString(",§")}")
        }
    }
}
