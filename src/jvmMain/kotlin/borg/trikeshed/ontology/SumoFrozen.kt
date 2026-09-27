package borg.trikeshed.ontology

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream

/**
 * Frozen SUMO: the projected [SumoTaxonomy] plus the WordNet noun lexicon (lemma, SUMO term id),
 * as one binary resource. Built once from the pinned corpus by [main]; read at launch in place of
 * parsing KIF. Strings are UTF-8 with an int length; arrays carry an int count.
 */
object SumoFrozen {
    const val RESOURCE = "sumo/frozen.bin"
    private const val MAGIC = 0x53554d33 // "SUM3": every sense (CSR) and the axiom relation (CSR)

    /**
     * The lexicon is CSR: senses of lemma i are `lexiconTerms[lexiconStarts[i] until lexiconStarts[i+1]]`,
     * preferred first. The axiom relation is CSR over term ids: `relatedIds[relatedStarts[t] until relatedStarts[t+1]]`.
     */
    class Image(
        val taxonomy: SumoTaxonomy, val lexiconLemmas: Array<String>, val lexiconStarts: IntArray, val lexiconTerms: IntArray,
        val relatedStarts: IntArray, val relatedIds: IntArray,
    )

    fun write(out: DataOutputStream, image: Image) {
        val t = image.taxonomy
        out.writeInt(MAGIC)
        strings(out, t.names)
        out.writeInt(t.isClass.size); t.isClass.forEach { out.writeBoolean(it) }
        ints(out, t.subclassEdges); ints(out, t.instanceEdges); ints(out, t.disjointPairs)
        strings(out, t.domainKeys); ints(out, t.domainClass); bools(out, t.domainSubclass)
        strings(out, t.rangeKeys); ints(out, t.rangeClass); bools(out, t.rangeSubclass)
        ints(out, t.counts)
        strings(out, image.lexiconLemmas); ints(out, image.lexiconStarts); ints(out, image.lexiconTerms)
        ints(out, image.relatedStarts); ints(out, image.relatedIds)
    }

    fun read(input: InputStream): Image {
        val d = DataInputStream(input.buffered(1 shl 16))
        check(d.readInt() == MAGIC) { "$RESOURCE: bad magic" }
        val names = strings(d)
        val isClass = BooleanArray(d.readInt()) { d.readBoolean() }
        val taxonomy = SumoTaxonomy(
            names, isClass, ints(d), ints(d), ints(d),
            strings(d), ints(d), bools(d),
            strings(d), ints(d), bools(d),
            ints(d),
        )
        return Image(taxonomy, strings(d), ints(d), ints(d), ints(d), ints(d))
    }

    private fun strings(out: DataOutputStream, a: Array<String>) {
        out.writeInt(a.size)
        for (s in a) { val b = s.encodeToByteArray(); out.writeInt(b.size); out.write(b) }
    }
    private fun ints(out: DataOutputStream, a: IntArray) { out.writeInt(a.size); a.forEach { out.writeInt(it) } }
    private fun bools(out: DataOutputStream, a: BooleanArray) { out.writeInt(a.size); a.forEach { out.writeBoolean(it) } }
    private fun strings(d: DataInputStream): Array<String> = Array(d.readInt()) { ByteArray(d.readInt()).also(d::readFully).decodeToString() }
    private fun ints(d: DataInputStream): IntArray = IntArray(d.readInt()) { d.readInt() }
    private fun bools(d: DataInputStream): BooleanArray = BooleanArray(d.readInt()) { d.readBoolean() }

    /** Freeze the full (else pinned) corpus and the WordNet noun lexicon to `args[0]`. */
    @JvmStatic
    fun main(args: Array<String>) {
        val forms = SumoCorpus.forms().toList()
        val taxonomy = SumoClassifier.taxonomy(forms)
        val (lemmas, starts, terms) = SumoCorpus.parsedLexicon(taxonomy)
        val (relStarts, relIds) = SumoCorpus.parsedRelated(taxonomy, forms)
        val file = File(args[0]).apply { parentFile.mkdirs() }
        DataOutputStream(file.outputStream().buffered(1 shl 16)).use { write(it, Image(taxonomy, lemmas, starts, terms, relStarts, relIds)) }
        println("froze ${taxonomy.names.size} terms, ${lemmas.size} lemmas, ${terms.size} senses, ${relIds.size / 2} axiom pairs → $file (${file.length()} bytes)")
    }
}
