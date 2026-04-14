package cursors.context

import kotlin.coroutines.CoroutineContext
import vec.macros.Vect02

sealed class RecordBoundary : CoroutineContext.Element {
    companion object {
        val boundaryKey: CoroutineContext.Key<RecordBoundary> = object : CoroutineContext.Key<RecordBoundary> {}
    }

    override val key: CoroutineContext.Key<RecordBoundary> get() = boundaryKey
}

class TokenizedRow(val tokenizer: (String) -> List<String>) : RecordBoundary()

class FixedWidth(
    val recordLen: Int,
    val coords: Vect02<Int, Int>,
    val endl: () -> Byte? = { '\n'.code.toByte() },
    val pad: () -> Byte? = { ' '.code.toByte() },
) : RecordBoundary()

