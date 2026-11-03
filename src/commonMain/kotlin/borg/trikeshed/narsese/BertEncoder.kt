package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlin.coroutines.CoroutineContext

/** Offsets, when supplied by the reader, are UTF-16 offsets into [sentence]. */
data class BertToken(val lemma: String, val sentence: String, val start: Int? = null, val end: Int? = null)
/** Exact matches complete normalized BERT basic tokens; Explicit uses the caller's UTF-16 offsets.
 * Stem is an ASCII-word fallback. The remaining values indicate no complete selection. */
enum class BertSpan { Exact, Stem, Explicit, Missing, Ambiguous, Truncated }
val BertSpan.resolved: Boolean get() = this == BertSpan.Exact || this == BertSpan.Stem || this == BertSpan.Explicit
data class BertVector(val token: BertToken, val span: BertSpan, val values: FloatArray?)

/** Contextual word vectors, not a truth judge or a generative document model. */
interface BertEncoder : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<BertEncoder>
    override val key: CoroutineContext.Key<BertEncoder> get() = Key
    val model: String
    suspend fun encode(tokens: Series<BertToken>): Series<BertVector>
    suspend fun drain()
}
