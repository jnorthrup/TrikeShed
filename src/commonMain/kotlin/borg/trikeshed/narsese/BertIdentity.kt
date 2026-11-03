package borg.trikeshed.narsese

import borg.trikeshed.job.Sha256
import borg.trikeshed.lib.*
import borg.trikeshed.util.toLowerHex

/** Identity of the effective loaded F32 encoder and WordPiece rules, not a signature on its source files. */
internal object BertIdentity {
    const val VERSION = "bert-common-f32-2"

    fun digest(weights: BertWeights, tokenizer: BertWordPiece, check: () -> Unit = {}): String {
        val hash = Sha256()
        val buffer = ByteArray(65_536)
        var used = 0
        fun flush() {
            if (used > 0) {
                check()
                hash.update(buffer, 0, used)
                used = 0
            }
        }
        fun byte(value: Int) {
            if (used == buffer.size) flush()
            buffer[used++] = value.toByte()
        }
        fun int(value: Int) { for (shift in 0 until 32 step 8) byte(value ushr shift) }
        fun long(value: Long) { for (shift in 0 until 64 step 8) byte((value ushr shift).toInt()) }
        fun text(value: String) {
            val encoded = value.encodeToByteArray(throwOnInvalidSequence = true)
            int(encoded.size)
            for (item in encoded) byte(item.toInt())
        }
        check()
        text(VERSION)
        val config = weights.config
        for (value in s_[config.hiddenSize, config.numHiddenLayers, config.numAttentionHeads,
            config.intermediateSize, config.maxPositionEmbeddings, config.typeVocabSize,
            config.vocabSize, config.padTokenId].view) int(value)
        long(config.layerNormEps.toRawBits())
        text(config.hiddenAct)
        int(tokenizer.maxLength)
        int(tokenizer.rows)
        val vocabulary = tokenizer.vocabulary.entries.sortedBy { it.value }
        int(vocabulary.size)
        for ((word, id) in vocabulary) { int(id); text(word) }
        val names = weights.tensors.keys.sorted()
        int(names.size)
        for (name in names) {
            val tensor = weights[name]
            text(name)
            int(tensor.a.size)
            for (extent in tensor.a.view) int(extent)
            int(tensor.b.size)
            for (value in tensor.b) int(value.toRawBits())
        }
        flush()
        check()
        return hash.digest().toLowerHex()
    }
}
