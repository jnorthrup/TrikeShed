package borg.trikeshed.graal.console

import borg.trikeshed.lib.*
import borg.trikeshed.ontology.SumoFrozen
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

/** The store's typing names classes the frozen SUMO image holds, and its territories stand inside those classes' rooms. */
class StoreClassesTest {
    @Test
    fun everyClassNamedIsAClassOfTheImage() {
        val h = SumoFrozen.hyperarchy
        assumeTrue(h != null, "the frozen SUMO image (${SumoFrozen.RESOURCE}) is not on the test classpath")
        for (c in StoreClasses.BY_EXTENSION.values.toSet() + "ComputerFile") assertTrue(h!!.index(c) >= 0, "$c is a class of the image")
        val rows = _l[TerrainRow("projects/law/notes.md", 120.0, 1, 1, 0), TerrainRow("projects/law/ruling.pdf", 4000.0, 2, 1, 0),
            TerrainRow("forge/Main.class", 900.0, 3, 1, 0, Topology.bit("classes")), TerrainRow("projects/README", 10.0, 4, 1, 0),
            TerrainRow("runtime:live/java.lang.String", 1e6, 0, 1, 0, Topology.bit("live"))]
        assertEquals(_l["Text", "Document", "SoftwareFile", null, null], rows.map { StoreClasses.classOf(it) })
        val stands = StoreClasses.ground(rows)
        assertEquals(_l["store/untyped/projects", "store/untyped/runtime:live", "store/Document/projects", "store/SoftwareFile/forge", "store/Text/projects"], stands.view.map { it.id })
        val text = stands.view.first { it.id == "store/Text/projects" }
        assertTrue(h!!.room(h.index("Text")).contains(h.home(text.path)), "the Text territory of projects is inside Text's room")
        assertEquals(_l["projects", "1 document · 120B", "Text"], text.label.view.toList())
        val slashed = StoreClasses.ground(_l[TerrainRow("/memories/a/x.md", 10.0, 1, 1, 0), TerrainRow("/memories/b/y.md", 10.0, 2, 1, 0)])
        assertEquals(_l["store/Text/memories"], slashed.view.map { it.id }, "an id that opens with a slash is of the database named after it, not a database of its own")
        assertEquals("2 documents · 20B", slashed.view.single().label.view.toList()[1])
    }
}
