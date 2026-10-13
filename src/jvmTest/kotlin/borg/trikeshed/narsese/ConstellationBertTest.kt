package borg.trikeshed.narsese

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lib.*
import borg.trikeshed.ontology.SumoClassId
import borg.trikeshed.ontology.SumoCorpus
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.io.File
import kotlin.test.*

class ConstellationBertTest {
    @TempDir lateinit var directory: Path
    private val files = JvmFileOperations()

    @Test fun existingReadingUsesRetainedLabelsWithoutJevOrNarsWrites() = runBlocking {
        val state = directory.toFile()
        val books = File(state, "constellations/books").apply { mkdirs() }
        val classes = SumoCorpus.nounSenses("bank").map { SumoCorpus.classifier.className(SumoClassId(it)) }.distinct()
        assertTrue(classes.size >= 2)
        val table = File(books, "prior.jev/0.json").apply { parentFile.mkdirs() }
        val bytes = jsonOf(mapOf("senses" to List(8) { i -> mapOf("lemma" to "bank", "sentence" to "Training context $i",
            "source" to "label-$i", "probabilities" to mapOf(classes[0] to 0.9, classes[1] to 0.1)) })).encodeToByteArray()
        files.write(table.path, bytes)
        val corpus = BertSenseCorpus(files, books.path)
        val retained = corpus.retain("prior", "revision-one")
        assertEquals(1, retained.size)
        assertFalse(table.exists())
        val archived = File(retained[0], "0.json")
        assertContentEquals(bytes, archived.readBytes())
        val encoder = BertSenseElementTest.Encoder()
        val lane = BertSenseElement(this, { encoder })
        val nodes = ConstellationNodes(state, ConfixBlackboard(), bert = lane, files = files)
        try {
            val read = listOf(mapOf<String, Any?>("text" to "The bank holds the account.", "tokens" to 6,
                "concepts" to listOf(mapOf("lemma" to "bank", "word" to "bank", "class" to classes[1], "is" to emptyList<String>()))))
            val result = nodes.bertReading("new-book", read, listOf("prior"))
            val concept = (result[0]["concepts"] as List<*>)[0] as Map<*, *>
            assertEquals(classes[0], concept["class"])
            assertEquals(classes[1], concept["read"])
            assertEquals("bert", concept["by"])
            val receipt = concept["bert"] as Map<*, *>
            assertEquals("Supported", receipt["status"])
            val neighbors = receipt["neighbors"] as List<*>
            assertEquals(7, neighbors.size)
            assertTrue(neighbors.all { (it as Map<*, *>)["table"] == archived.path })
            assertContentEquals(bytes, archived.readBytes())
            assertFalse(File(state, "constellations/senses.rows").exists())
            assertFalse(table.exists(), "inference cannot recreate a Jev judgment table")
            val abstained = nodes.bertReading("new-book", result, emptyList())
            val unchanged = (abstained[0]["concepts"] as List<*>)[0] as Map<*, *>
            assertEquals(classes[1], unchanged["class"])
            assertNull(unchanged["by"])
            assertEquals("InsufficientEvidence", (unchanged["bert"] as Map<*, *>)["status"])
        } finally { nodes.drain() }
        assertTrue(encoder.closed)
    }

    @Test fun retentionCannotOverwriteAnEarlierGeneration() {
        val books = directory.toFile()
        val source = File(books, "book.jev").apply { mkdirs() }
        File(source, "0.json").writeText("original")
        val corpus = BertSenseCorpus(files, books.path)
        corpus.retain("book", "same-revision")
        source.mkdirs(); File(source, "0.json").writeText("new")
        assertFails { corpus.retain("book", "same-revision") }
        assertEquals("original", File(books, "book.jev.history/same-revision/0.json").readText())
        assertEquals("new", File(source, "0.json").readText())
    }
}
