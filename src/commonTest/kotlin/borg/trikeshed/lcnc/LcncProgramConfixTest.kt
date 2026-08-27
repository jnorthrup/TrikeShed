package borg.trikeshed.lcnc

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * No JavaScript in this loop: a program stored as Confix JSON, read straight
 * out of the real Oroboros attachment store (the same `panels/<name>` path
 * `PatchWire` already writes), parsed into an [LcncProgram], and dived into
 * by [ProgramNavigator] — the whole chain in Kotlin.
 */
class LcncProgramConfixTest {

    /** The exact document shape `panels.html` wrote to `/api/panels/kanban` earlier this session. */
    private val realKanbanJson = """
        {"nodes":[
          {"id":"n1","type":"timer","x":30,"y":60,"params":{"seconds":"5"},"collapsed":false},
          {"id":"n2","type":"http.get","x":250,"y":60,"params":{"path":"/api/board"},"collapsed":false},
          {"id":"n5","type":"list.groupBy","x":690,"y":20,"params":{"key":"status"},"collapsed":false},
          {"id":"n6","type":"dom.board","x":910,"y":20,"params":{"idField":"id","titleField":"title"},"collapsed":false}
        ],"wires":[
          {"from":["n1","tick"],"to":["n2","trigger?"]},
          {"from":["n2","json"],"to":["n5","x"]},
          {"from":["n5","groups"],"to":["n6","groups"]}
        ]}
    """.trimIndent()

    @Test
    fun parsesTheRealStoredKanbanDocumentShapeByteForByte() {
        val program = LcncProgramConfix.fromJson("kanban", realKanbanJson)
        assertEquals(4, program.nodes.size)
        assertEquals(3, program.wires.size)
        assertEquals("timer", program.nodes[0].type)
        assertEquals("5", program.nodes[0].params["seconds"])
        assertEquals("dom.board", program.nodes[3].type)
        assertEquals("id", program.nodes[3].params["idField"])
        assertEquals("n1", program.wires[0].fromNode)
        assertEquals("trigger?", program.wires[0].toPort)
    }

    @Test
    fun theParsedProgramActuallyTopologicallySorts() {
        // Proof this isn't just field-parsing: the wire graph in the real
        // document is a real DAG once parsed, and topo() (already tested
        // separately) accepts it without throwing.
        val program = LcncProgramConfix.fromJson("kanban", realKanbanJson)
        val order = program.topo()
        val ids = (0 until order.size).map { order[it].id }
        assertTrue(ids.indexOf("n1") < ids.indexOf("n2"), "timer before http.get: $ids")
        assertTrue(ids.indexOf("n2") < ids.indexOf("n5"), "http.get before groupBy: $ids")
        assertTrue(ids.indexOf("n5") < ids.indexOf("n6"), "groupBy before dom.board: $ids")
    }

    @Test
    fun roundTripsThroughJsonWithoutLoss() {
        val original = LcncProgramConfix.fromJson("kanban", realKanbanJson)
        val reparsed = LcncProgramConfix.fromJson("kanban", LcncProgramConfix.toJson(original))
        assertEquals(original.nodes.size, reparsed.nodes.size)
        assertEquals(original.wires.size, reparsed.wires.size)
        for (i in 0 until original.nodes.size) {
            assertEquals(original.nodes[i].id, reparsed.nodes[i].id)
            assertEquals(original.nodes[i].type, reparsed.nodes[i].type)
            assertEquals(original.nodes[i].params, reparsed.nodes[i].params)
        }
    }

    private fun gateway(): CouchAttachmentGateway {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        return CouchAttachmentGateway(couchStore, cas)
    }

    @Test
    fun saveThenDiveIntoAProgramThroughTheRealAttachmentStoreNoHttpInvolved() = runTest {
        val gw = gateway()
        val kanban = LcncProgramConfix.fromJson("kanban", realKanbanJson)
        saveProgramToOroboros(gw, kanban)

        // Proof the bytes are real store content, not an in-memory echo —
        // the same assertion PatchWireTest makes for the browser path.
        val (_, bytes) = gw.getAttachment("panels/kanban")!!
        assertTrue(bytes.decodeToString().contains("dom.board"))

        val nav = ProgramNavigator(LcncProgram.EMPTY, oroborosProgramLoader(gw))
        val result = nav.diveInto("kanban")
        assertEquals(ProgramNavigator.DiveResult.Ok, result)
        assertEquals("kanban", nav.current.name)
        assertEquals(4, nav.current.nodes.size, "the dived-into program is the REAL one just saved, not a stub")

        assertTrue(nav.pop())
        assertEquals("", nav.current.name, "pop restores the empty root exactly")
    }

    @Test
    fun divingIntoANameThatWasNeverSavedIsNotFoundNotACrash() = runTest {
        val gw = gateway()
        val nav = ProgramNavigator(LcncProgram.EMPTY, oroborosProgramLoader(gw))
        val result = nav.diveInto("never-saved")
        assertEquals(ProgramNavigator.DiveResult.NotFound("never-saved"), result)
    }
}
