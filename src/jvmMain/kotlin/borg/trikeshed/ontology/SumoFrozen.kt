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
    private const val MAGIC = 0x53554d31 // "SUM1"

    class Image(val taxonomy: SumoTaxonomy, val lexiconLemmas: Array<String>, val lexiconTerms: IntArray)

    fun write(out: DataOutputStream, image: Image) {
        val t = image.taxonomy
        out.writeInt(MAGIC)
        strings(out, t.names)
        out.writeInt(t.isClass.size); t.isClass.forEach { out.writeBoolean(it) }
        ints(out, t.subclassEdges); ints(out, t.instanceEdges); ints(out, t.disjointPairs)
        strings(out, t.domainKeys); ints(out, t.domainClass); bools(out, t.domainSubclass)
        strings(out, t.rangeKeys); ints(out, t.rangeClass); bools(out, t.rangeSubclass)
        ints(out, t.counts)
        strings(out, image.lexiconLemmas); ints(out, image.lexiconTerms)
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
        return Image(taxonomy, strings(d), ints(d))
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
        val taxonomy = SumoCorpus.parsedTaxonomy()
        val (lemmas, terms) = SumoCorpus.parsedLexicon(taxonomy)
        val file = File(args[0]).apply { parentFile.mkdirs() }
        DataOutputStream(file.outputStream().buffered(1 shl 16)).use { write(it, Image(taxonomy, lemmas, terms)) }
        println("froze ${taxonomy.names.size} terms, ${lemmas.size} lemmas → $file (${file.length()} bytes)")
    }
}
