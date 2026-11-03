package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyMap
import borg.trikeshed.userspace.nio.file.spi.FileOperations

/** Read existing judgments through the filesystem SPI; inference never writes to these tables. */
class BertSenseCorpus(private val files: FileOperations, private val books: String,
                      private val judgmentSuffix: String = "jev") {
    fun labels(names: Series<String>, lemmas: Set<String>? = null): Series<BertSenseLabel> {
        val labels = LinkedHashMap<Pair<String, String>, BertSenseLabel>()
        for (name in names.view) {
            require(name.matches(Regex("[A-Za-z0-9._-]+")) && name != "." && name != "..")
            val history = files.resolvePath(books, "$name.$judgmentSuffix.history")
            val directories = files.listDir(history).sorted().map { files.resolvePath(history, it) } +
                files.resolvePath(books, "$name.$judgmentSuffix")
            for (directory in directories) for (file in files.listDir(directory)
                .filter { it.matches(Regex("[0-9]+\\.json")) && it.removeSuffix(".json").toIntOrNull() != null }
                .sortedBy { it.removeSuffix(".json").toInt() }) {
                val table = files.resolvePath(directory, file)
                for (value in (reifyMap(files.readString(table))["senses"] as? List<*>).orEmpty()) {
                    val row = value as? Map<*, *> ?: continue
                    val lemma = row["lemma"] as? String ?: continue
                    if ((lemmas != null && lemma !in lemmas) || row["by"] == "bert") continue
                    val source = (row["source"] as? String)?.takeIf { it.isNotBlank() } ?: continue
                    // A later judgment replaces the earlier one even when it abstains
                    // or cannot supply a valid label. Do not revive a superseded vote.
                    labels.remove(source to lemma)
                    if (row["refused"] == true) continue
                    val sentence = row["sentence"] as? String ?: continue
                    val probabilities = row["probabilities"] as? Map<*, *> ?: continue
                    if (probabilities.isEmpty() || probabilities.any { (key, value) ->
                        key !is String || key.isBlank() || value !is Number ||
                            !value.toDouble().isFinite() || value.toDouble() !in 0.0..1.0
                    }) continue
                    val choice = probabilities.entries.maxByOrNull { (it.value as Number).toDouble() } ?: continue
                    val p = (choice.value as Number).toDouble()
                    if (choice.key == SenseMemory.NONE || p == 0.0 || probabilities.values.count { (it as Number).toDouble() == p } != 1 ||
                        sentence.length > 16_384) continue
                    // Current tables retain the provider's explicit selected sense. A
                    // contradictory or malformed selection is not evidence for the
                    // probability maximum (nor for the selected alternative).
                    // Older probability-only rows retain their existing interpretation.
                    if (row.containsKey("sense") && row["sense"] != choice.key) continue
                    // Latest generation, then active tables win for the same original source.
                    labels[source to lemma] = BertSenseLabel(source, choice.key as String,
                        BertToken(lemma, sentence), table)
                }
            }
        }
        return labels.values.toList().toSeries()
    }

    /** Retain prior readings and judgments by rename, without copying a corpus or deleting it. */
    fun retain(name: String, revision: String, judgmentSuffixes: Series<String> = s_[judgmentSuffix]): Series<String> {
        require(name.matches(Regex("[A-Za-z0-9._-]+")) && name != "." && name != "..")
        require(revision.matches(Regex("[A-Za-z0-9-]+")))
        val retained = ArrayList<String>()
        for (suffix in (s_["readings"] + judgmentSuffixes).view) {
            require(suffix.matches(Regex("[A-Za-z0-9._-]+")) && suffix != "." && suffix != "..")
            val source = files.resolvePath(books, "$name.$suffix")
            if (!files.exists(source)) continue
            val history = files.resolvePath(books, "$name.$suffix.history")
            files.mkdirs(history)
            val destination = files.resolvePath(history, revision)
            files.move(source, destination)
            retained.add(destination)
        }
        return retained.toSeries()
    }
}
