package borg.trikeshed.narsese

import java.io.File

/** Distribution probe for [SkillOverlapNode]: class-set sizes, vocabulary, and the top pairs by Jaccard. */
object SkillOverlapCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val common = args.getOrNull(1)?.toFloatOrNull() ?: 0.25f
        val raw = SkillOverlapNode.classSets(File(args[0], "skills"))
        val vocab = SkillOverlapNode.vocabulary(raw, common)
        val sets = raw.map { (n, s) -> n to (s andNot vocab) }
        println("[overlap] ${sets.size} skills, vocabulary ${vocab.cardinality}, class-set sizes " +
            sets.map { it.second.cardinality }.sorted().let { "min ${it.first()} median ${it[it.size / 2]} max ${it.last()}" })
        val pairs = ArrayList<Triple<String, String, Float>>()
        for (i in sets.indices) for (j in i + 1 until sets.size) {
            val (a, sa) = sets[i]; val (b, sb) = sets[j]
            val u = (sa or sb).cardinality
            if (u > 0) pairs.add(Triple(a, b, (sa and sb).cardinality.toFloat() / u))
        }
        pairs.sortByDescending { it.third }
        val byName = sets.toMap()
        for ((a, b, jac) in pairs.take(15)) {
            val spec = SkillOverlapNode.specific(byName.getValue(a) and byName.getValue(b)).toIntArray()
                .map { borg.trikeshed.ontology.SumoCorpus.classifier.className(borg.trikeshed.ontology.SumoClassId(it)) }
            println("[pair] ${"%.3f".format(jac)}  $a ~ $b  ${spec.size} specific: ${spec.take(10)}")
        }
    }
}
