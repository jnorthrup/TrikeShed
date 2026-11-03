package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.*

class BertPythonSleeveTest {
    @org.junit.jupiter.api.io.TempDir lateinit var root: Path

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
