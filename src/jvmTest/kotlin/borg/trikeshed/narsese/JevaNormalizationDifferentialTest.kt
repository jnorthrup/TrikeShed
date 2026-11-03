package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import java.text.Normalizer
import kotlin.random.Random
import kotlin.test.*

class JevaNormalizationDifferentialTest {
    @Test fun variedCanonicalRunsMatchTheJdkNormalizer() {
        val alphabet = _l["a", "e", "é", " ", "\u0301", "\u0300", "\u0316", "\u0327", "\u0345", "\u034f",
            "\u1100", "\u1161", "\u11a8", "각", "🚀"]
        val random = Random(0x4a657661)
        repeat(600) { sample ->
            val input = buildString {
                repeat(random.nextInt(1, 129)) { append(alphabet[random.nextInt(alphabet.size)]) }
            }
            assertEquals(Normalizer.normalize(input, Normalizer.Form.NFC), JevaTokenizer.normalize(input), "sample $sample")
        }
        val long = "a" + "\u0301".repeat(200) + "\u0300".repeat(200) + "\u0316".repeat(200)
        assertEquals(Normalizer.normalize(long, Normalizer.Form.NFC), JevaTokenizer.normalize(long))
    }
}
