package borg.trikeshed.narsese

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lib.*
import borg.trikeshed.ontology.SumoCorpus
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.rdf.QuadStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** What a constellation's join and restore hand the RDF sink; synthetic saved books, no model. */
class ConstellationRdfSinkTest {
    @TempDir lateinit var directory: Path

    val prefix = "<https://trikeshed.borg/forge#constellation/law/"

    /** Two saved books that state one norm, so the constellation restates it. */
    fun books() {
        val classId = requireNotNull(SumoCorpus.classifier.classId("Human")).value
        val bookDir = directory.resolve("constellations/books")
        Files.createDirectories(bookDir)
        for (name in _l["one", "two"]) {
            val statement = _m["bearer" j "tenant", "modality" j "may", "action" j "occupy",
                "object" j "home", "condition" j "if rent be due", "bearerClass" j classId]
            val data = _m["name" j name, "work" j "synthetic", "date" j "2026",
                "headings" j _l["fixture"], "statements" j _l[statement], "support" j _l[_l[0]],
                "facts" j emptyList<String>(), "sections" j _l["urn:sha256:$name"]]
            Files.writeString(bookDir.resolve("$name.json"), jsonOf(data))
        }
    }

    @Test fun aJoinPutsTheRestatedNormInTheSinkUnderTheConstellationsPrefix(): Unit = runBlocking {
        books()
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard.empty(), scope = this)
        val quads = QuadStore()
        val replacing = ArrayList<String>()
        nodes.rdfSink = { prefix, turtle -> replacing.add(prefix); quads.drop(prefix); quads.load(turtle) }
        val runners = mutableMapOf<String, LcncNodeRunner>()
        nodes.register(runners)
        suspend fun join(book: String) = runners.getValue(ConstellationNodes.JOIN).run(
            LcncNode("join", ConstellationNodes.JOIN, params = _m["constellation" j "law"]), _m["book" j book])
        try {
            join("one")
            assertEquals(0, quads.size, "one book states the norm: nothing is restated")
            val out = join("two")
            assertTrue(quads.size > 0)
            assertEquals(QuadStore().load(out["rdf"] as String).size, quads.size)
            val filed = quads.size
            join("two")
            assertEquals(filed, quads.size, "joined again, the same atoms stand once")
            assertEquals(_l[prefix, prefix, prefix], replacing)
            assertEquals(filed, quads.drop(prefix), "every atom is under the constellation's prefix")
            assertEquals(0, quads.size)
        } finally { nodes.drain() }
    }

    @Test fun aRestoreStandsEverySavedConstellationInTheSink(): Unit = runBlocking {
        books()
        Files.writeString(directory.resolve("constellations/law.members"), "one\ntwo\n")
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard.empty(), scope = this)
        val quads = QuadStore()
        nodes.rdfSink = { prefix, turtle -> quads.drop(prefix); quads.load(turtle) }
        try {
            nodes.restore()
            assertTrue(quads.size > 0)
            assertEquals(quads.size, quads.drop(prefix))
        } finally { nodes.drain() }
    }
}
