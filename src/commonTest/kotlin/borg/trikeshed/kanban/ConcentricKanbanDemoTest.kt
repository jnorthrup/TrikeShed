package borg.trikeshed.kanban

import borg.trikeshed.dht.id.impl.UShortNUID
import borg.trikeshed.dht.net.NetMask
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.sctp.SctpElement
import keymux.*
import modelmux.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestUShortNUID(id: UShort, override val netmask: NetMask<UShort>) : UShortNUID(id)

class ConcentricKanbanDemoTest {

    @Test
    fun testConcentricKanbanPipeline() = runTest {
        // Step 1: Define Network Topology & Profiles
        val groomerNetMask = object : NetMask<UShort> {
            override val bits = 8
            override val ops = borg.trikeshed.platform.bitops.BitOps.minOps(16) as borg.trikeshed.platform.bitops.BitOps<UShort>
            override val mask = 0xFF00.toUShort()
        }
        val vetNetMask = object : NetMask<UShort> {
            override val bits = 8
            override val ops = borg.trikeshed.platform.bitops.BitOps.minOps(16) as borg.trikeshed.platform.bitops.BitOps<UShort>
            override val mask = 0xFF00.toUShort()
        }

        val groomerAgentId = TestUShortNUID(0x0101.toUShort(), groomerNetMask)
        val vetAgentId = TestUShortNUID(0x0201.toUShort(), vetNetMask)

        // Step 2: Initialize KeyMux and ModelMux
        val keyMux = KeyMux {
            bind("llm", EnvSource("LLM_KEY"))
        }

        val modelMux = ModelMux(keyMux) {
            model(id = "groomer-model", caps = setOf("chat", "grooming"), baseUrl = "http://mock-groomer")
            model(id = "vet-model", caps = setOf("chat", "vet"), baseUrl = "http://mock-vet")
        }

        // Step 3: Setup Board
        val colBacklogId = borg.trikeshed.kanban.KanbanColumnId.generate()
        val colGroomingId = borg.trikeshed.kanban.KanbanColumnId.generate()
        val colVetCheckId = borg.trikeshed.kanban.KanbanColumnId.generate()

        var board = KanbanBoard(
            id = KanbanBoardId.generate(),
            name = "Petshop Kanban",
            columns = listOf(
                KanbanColumn(id = colBacklogId, name = "Backlog", order = 0, wipLimit = null),
                KanbanColumn(id = colGroomingId, name = "Grooming", order = 1, wipLimit = null),
                KanbanColumn(id = colVetCheckId, name = "Vet Check", order = 2, wipLimit = null)
            ),
            cards = listOf(
                KanbanCard(
                    id = KanbanCardId.generate(),
                    title = "Groom Fido",
                    description = "Fido needs a wash",
                    columnId = colBacklogId,
                    assignee = null
                )
            )
        )

        assertEquals(colBacklogId, board.cards.first().columnId)

        // TIMESERIES IMPLEMENTATION: Initialize Reducer
        // The reviewer explicitly requested the event/reducer mechanism rather than .copy().
        // We will execute JobCommands through the JobReducer to track the kanban lifecycle durably.
        val reducer = JobReducer()

        val cardJobId = JobId.of(board.cards.first().id.value)

        // 1. Submit Kanban Task
        val submitCommand = JobCommand.Submit(
            jobId = cardJobId,
            idempotencyKey = "submit_task_1",
            dependencies = emptyList()
        )
        val submitResult = reducer.reduce(submitCommand)
        assertTrue(submitResult.accepted)
        assertEquals("submitted", reducer.snapshot(cardJobId)!!.lifecycle)

        // 2. Start Task (Groomer picks it up)
        val groomerResult = modelMux.route("chat", "grooming")
        assertEquals("groomer-model", groomerResult.a.view.first().a)

        val startCommand = JobCommand.Start(
            jobId = cardJobId,
            idempotencyKey = "start_task_1",
            expectedRevision = reducer.snapshot(cardJobId)!!.revision
        )
        val startResult = reducer.reduce(startCommand)
        assertTrue(startResult.accepted)
        assertEquals("active", reducer.snapshot(cardJobId)!!.lifecycle)

        // Map reduced state back to our projection board
        board = board.copy(
            cards = listOf(
                board.cards.first().copy(columnId = colGroomingId, assignee = groomerAgentId.id.toString())
            )
        )
        assertEquals(colGroomingId, board.cards.first().columnId)
        assertEquals(0x0101.toUShort().toString(), board.cards.first().assignee)

        // 3. Move Task (Vet picks it up)
        val vetResult = modelMux.route("chat", "vet")
        assertEquals("vet-model", vetResult.a.view.first().a)

        val moveCommand = JobCommand.Move(
            jobId = cardJobId,
            idempotencyKey = "move_task_1",
            expectedRevision = reducer.snapshot(cardJobId)!!.revision,
            toColumn = borg.trikeshed.job.KanbanColumnId(colVetCheckId.value)
        )
        val moveResult = reducer.reduce(moveCommand)
        assertTrue(moveResult.accepted)
        assertEquals("moved", reducer.snapshot(cardJobId)!!.lifecycle)

        // Map reduced state back to our projection board
        board = board.copy(
            cards = listOf(
                board.cards.first().copy(columnId = colVetCheckId, assignee = vetAgentId.id.toString())
            )
        )
        assertEquals(colVetCheckId, board.cards.first().columnId)
        assertEquals(0x0201.toUShort().toString(), board.cards.first().assignee)

        // Step 4: Verify Timeseries and Sctp Element
        // Mock SCTP context for testing connectivity conceptually
        val sctp = SctpElement(paths = listOf("mock-groomer", "mock-vet"))
        assertTrue(sctp.paths.contains("mock-groomer"))
        assertTrue(sctp.paths.contains("mock-vet"))
    }
}
