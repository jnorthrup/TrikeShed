package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.test.*

class BertPythonSleeveTest {
    @org.junit.jupiter.api.io.TempDir lateinit var root: Path

    @Test fun sourceTreeEncoderCapturesTargetsBeforeWaitingForGate(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = object : BertEncoder {
            override val model: String = "fixture"
            override suspend fun encode(tokens: Series<BertToken>): Series<BertVector> {
                entered.complete(Unit)
                release.await()
                return tokens.size j { i -> BertVector(tokens[i], BertSpan.Exact, _a[1f]) }
            }
            override suspend fun drain() {}
        }
        val sleeve = BertPythonSleeve.open(this, root, Path.of("utils/graalpy-sleeve/bert")) { backend }
        try {
            val first = async(Dispatchers.Default) { sleeve.encode(s_[BertToken("first", "first sentence")]) }
            withTimeout(10_000) { entered.await() }
            val original = BertToken("original", "original sentence")
            val tokens = arrayOf(original)
            val queued = async(start = CoroutineStart.UNDISPATCHED) { sleeve.encode(tokens.toSeries()) }
            assertFalse(queued.isCompleted)
            tokens[0] = BertToken("replacement", "replacement sentence")
            release.complete(Unit)
            withTimeout(10_000) { first.await() }
            val result = withTimeout(10_000) { queued.await() }
            assertEquals(original, result[0].token)
            assertContentEquals(_a[1f], result[0].values)

            var reads = 0
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                val owner = requireNotNull(currentCoroutineContext()[Job])
                val source: Series<BertToken> = 4 j { i: Int ->
                    reads++
                    if (i == 0) owner.cancel()
                    BertToken("word$i", "sentence")
                }
                sleeve.encode(source)
            }
            assertFailsWith<CancellationException> { cancelled.await() }
            assertEquals(1, reads, "cancelled capture must stop reading the oracle")
        } finally {
            release.complete(Unit)
            sleeve.drain()
        }
        assertFailsWith<IllegalStateException> { sleeve.encode(s_[BertToken("closed", "closed sentence")]) }
    }

    @Test fun sourceTreeEncoderCapturesTargetsBeforeInference(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = object : BertEncoder {
            override val model: String = "fixture"
            override suspend fun encode(tokens: Series<BertToken>): Series<BertVector> {
                entered.complete(Unit)
                release.await()
                return tokens.size j { i -> BertVector(tokens[i], BertSpan.Exact,
                    _a[if (tokens[i].lemma.startsWith("original")) 1f else 2f]) }
            }
            override suspend fun drain() {}
        }
        val sleeve = BertPythonSleeve.open(this, root, Path.of("utils/graalpy-sleeve/bert")) { backend }
        val tokens = Array(17) { i -> BertToken("original$i", "same sentence") }
        val expected = tokens.copyOf()
        try {
            val result = async(Dispatchers.Default) { sleeve.encode(tokens.toSeries()) }
            withTimeout(10_000) { entered.await() }
            tokens[0] = BertToken("replacement0", "same sentence")
            tokens[16] = BertToken("replacement16", "same sentence")
            release.complete(Unit)
            val vectors = withTimeout(10_000) { result.await() }
            assertEquals(expected.size, vectors.size)
            for (i in expected.indices) {
                assertEquals(expected[i], vectors[i].token, "target $i")
                assertContentEquals(_a[1f], vectors[i].values, "target $i")
            }
        } finally {
            release.complete(Unit)
            sleeve.drain()
        }
    }

    @Test fun sourceTreeEncoderRejectsReorderedTargets(): Unit = runBlocking {
        var reversed = true
        val backend = object : BertEncoder {
            override val model: String = "fixture"
            override suspend fun encode(tokens: Series<BertToken>): Series<BertVector> = tokens.size j { i ->
                val index = if (reversed) tokens.size - i - 1 else i
                BertVector(tokens[index], BertSpan.Exact, _a[index.toFloat(), 1f])
            }
            override suspend fun drain() {}
        }
        val sleeve = BertPythonSleeve.open(this, root, Path.of("utils/graalpy-sleeve/bert")) { backend }
        try {
            val tokens = s_[BertToken("bank", "bank by the river"), BertToken("river", "bank by the river")]
            assertFails { sleeve.encode(tokens) }
            reversed = false
            val vectors = sleeve.encode(tokens)
            assertEquals(tokens[0], vectors[0].token)
            assertContentEquals(_a[0f, 1f], vectors[0].values)
            assertEquals(tokens[1], vectors[1].token)
            assertContentEquals(_a[1f, 1f], vectors[1].values)
        } finally { sleeve.drain() }
    }
}
