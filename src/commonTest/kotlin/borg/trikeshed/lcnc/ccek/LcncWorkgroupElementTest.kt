package borg.trikeshed.lcnc.ccek

import borg.trikeshed.context.nuid.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.BoardApply
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reify
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.sync.Semaphore
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LcncWorkgroupElementTest {
    fun program(value: String = "first", subnet: String? = "local.review") = LcncProgram(
        "review", s_[LcncNode("value", "text.value", mapOf("value" to value))], emptySeriesOf(),
        LcncConfixControls(subnet = subnet),
    )

    class Rig {
        val board = ConfixBlackboard()
        val fanout = NuidFanoutElement()
        val vocabulary = LcncContracts.all().associateBy { it.type }
        val bindings = mutableListOf<ContentId>()
        var resolutions = 0
        var beforeBind: suspend (Workgroup, LcncProgram, ContentId) -> Unit = { _, _, _ -> }
        var execute: suspend (LcncProgram, ContentId) -> Any? = { program, cid -> cid j program.nodes[0].params["value"] }
        var dispatchQueued: suspend (LcncRunQueueEntry) -> Deferred<Any?> = { error("No queued fixture dispatch") }
        val element = LcncWorkgroupElement(board, fanout, { resolutions++; vocabulary }, { group, program, cid ->
            beforeBind(group, program, cid)
            bindings += cid
            fanout.register(group) { execute(program, cid) }
        }, dispatchQueued = { dispatchQueued(it) })

        fun put(program: LcncProgram) {
            board.put(LcncBlackboard.programKey(program.name),
                LcncBlackboard.programEntry(program.name, program, vocabulary), "test")
        }

        fun request(name: String = "review", scope: String = "local.review.worker") =
            nuid(Capability.Custom("lcnc", "program/$name"), Nonce.RandomBytes(), Subnet.parse(scope))

        suspend fun result(): Any? = assertNotNull(fanout.dispatch(request()).completion).await()

        fun entry(id: String, context: CoroutineContext = EmptyCoroutineContext): LcncRunQueueEntry =
            BoardApply.Committed("lcnc/run/$id", 1L, 1L, "queue/$id", ContentId.of(id.encodeToByteArray())) j context

        fun pinnedRequest(cid: ContentId) = nuid(Capability.Custom("lcnc", "program/review/${cid.value}"),
            Nonce.RandomBytes(), Subnet.parse("local.review.worker"))
    }

    suspend fun fixture(body: suspend (Rig) -> Unit) {
        val rig = Rig()
        rig.fanout.open()
        try { body(rig) }
        finally {
            withContext(NonCancellable) {
                try { rig.element.drain() } finally { rig.fanout.drain() }
            }
        }
        assertTrue(rig.element.supervisor.isCompleted)
        assertFalse(rig.element.supervisor.children.any())
    }

    @Test fun absentDeclarationRetainsCanonicalBytesAndExplicitSubnetRoundTrips() {
        val legacy = """{"nodes":[], "wires":[], "controls":{"humanOversight":true, "matingPoints":[]}, "kanban":null, "view":null, "seq":1}"""
        val empty = LcncProgram("legacy", emptySeriesOf(), emptySeriesOf())
        assertEquals(legacy, LcncProgramConfix.toJson(empty))
        assertEquals(ContentId.of(legacy.encodeToByteArray()).value, LcncBlackboard.cidOf(empty))
        val declared = program()
        assertEquals("local.review", LcncProgramConfix.fromJson(declared.name, LcncProgramConfix.toJson(declared)).controls.subnet)
        assertFalse(LcncProgramConfix.sameDefinition(declared, declared.copy(controls = LcncConfixControls())))
    }

    @Test fun explicitMalformedSubnetsAreRefused() {
        for (value in listOf(null, 1, true, emptyList<Any?>(), emptyMap<String, Any?>(), "", " ",
            ".local", "local.", "local..review", "local. review", "local.\nreview", "a".repeat(257))) {
            val raw = jsonOf(mapOf("nodes" to emptyList<Any?>(), "wires" to emptyList<Any?>(),
                "controls" to mapOf("subnet" to value)))
            assertFailsWith<IllegalArgumentException> { LcncProgramConfix.fromJson("invalid", raw) }
        }
    }

    @Test fun declarationRegistersFrozenActualDocumentAndIgnoresStaleEntryCid() = runTest {
        fixture { rig ->
            val original = program()
            val actual = program("board edit")
            val entry = LcncBlackboard.programEntry(original.name, original, rig.vocabulary) +
                ("document" to reify(LcncProgramConfix.toJson(actual)))
            rig.board.put(LcncBlackboard.programKey(actual.name), entry, "test")
            rig.element.open()
            val registration = rig.element.registrations.view.single()
            val cid = ContentId(LcncBlackboard.cidOf(actual))
            assertEquals(LcncBlackboard.programKey(actual.name), registration.a.name)
            assertEquals("local.review", registration.a.scope.toString())
            assertEquals(actual.name, registration.b.a)
            assertEquals(cid, registration.b.b)
            assertEquals(cid to "board edit", assertIs<Join<*, *>>(rig.result()).pair)
            assertNull(rig.fanout.dispatch(rig.request(scope = "local.other")).winner)
            assertNull(rig.fanout.dispatch(rig.request(name = "other")).winner)
            val resolutions = rig.resolutions
            repeat(8) { rig.board.put("lcnc/run/$it", mapOf("status" to "completed"), "test") }
            rig.element.refresh()
            assertEquals(resolutions, rig.resolutions, "Run receipts cannot reparse or resolve unchanged program declarations")
            assertEquals(listOf(cid), rig.bindings)
        }
    }

    @Test fun replacementDrainsAdmittedVersionAndSkipsAnObsoleteIntermediateDefinition() = runTest {
        fixture { rig ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = program()
            val firstCid = ContentId(LcncBlackboard.cidOf(first))
            rig.execute = { frozen, cid ->
                if (cid == firstCid) { started.complete(Unit); release.await() }
                cid j frozen.nodes[0].params["value"]
            }
            rig.put(first)
            rig.element.open()
            val admitted = rig.fanout.dispatch(rig.request())
            started.await()
            rig.put(program("obsolete"))
            val refreshed = async { rig.element.refresh() }
            runCurrent()
            val latest = program("latest")
            val latestCid = ContentId(LcncBlackboard.cidOf(latest))
            try {
                assertFalse(refreshed.isCompleted)
                assertNull(rig.fanout.slotOf(LcncBlackboard.programKey(first.name)))
                rig.put(latest)
            } finally { release.complete(Unit) }
            assertEquals(firstCid to "first", assertIs<Join<*, *>>(assertNotNull(admitted.completion).await()).pair)
            refreshed.await()
            assertEquals(listOf(firstCid, latestCid), rig.bindings)
            assertEquals(latestCid to "latest", assertIs<Join<*, *>>(rig.result()).pair)
            rig.board.remove(LcncBlackboard.programKey(first.name))
            rig.element.refresh()
            assertEquals(0, rig.element.registrations.size)
            assertNull(rig.fanout.dispatch(rig.request()).winner)
        }
    }

    @Test fun withdrawalInspectionAndActualTypeViolationsRetireTheirHandlers() = runTest {
        fixture { rig ->
            rig.put(program())
            rig.element.open()
            rig.put(program(subnet = null))
            rig.element.refresh()
            assertEquals(0, rig.element.registrations.size)
            assertTrue(rig.element.refusals.isEmpty())
            rig.put(program().copy(controls = LcncConfixControls(inspectionOnly = true, subnet = "local.review")))
            rig.element.refresh()
            assertContains(rig.element.refusals.getValue(LcncBlackboard.programKey("review")), "Inspection-only")
            val invalid = program().copy(wires = s_[LcncWire("absent", "value", "value", "missing")])
            val entry = LcncBlackboard.programEntry(invalid.name, invalid, rig.vocabulary) + ("violations" to emptyList<Any?>())
            rig.board.put(LcncBlackboard.programKey(invalid.name), entry, "test")
            rig.element.refresh()
            assertTrue(rig.element.refusals.isNotEmpty(), "Cached board violations cannot override actual checking")
            assertEquals(0, rig.element.registrations.size)
            rig.put(program("corrected"))
            rig.element.refresh()
            assertTrue(rig.element.refusals.isEmpty())
            assertEquals(ContentId(LcncBlackboard.cidOf(program("corrected"))) to "corrected",
                assertIs<Join<*, *>>(rig.result()).pair)
        }
    }

    @Test fun oneRefusedBindingDoesNotSuppressOtherDeclaredProgramsOrDeleteAnExistingOwner() = runTest {
        fixture { rig ->
            val occupied = Workgroup(LcncBlackboard.programKey("occupied"), Subnet.local,
                traitSpaceOf(Capability.Custom("lcnc", "program/occupied")))
            rig.fanout.register(occupied) { "existing owner" }
            rig.put(program().copy(name = "occupied"))
            rig.put(program().copy(name = "bad"))
            rig.put(program())
            rig.beforeBind = { _, frozen, _ -> if (frozen.name == "bad") error("CAS publication failed") }
            rig.element.open()
            assertEquals(listOf("review"), rig.element.registrations.view.map { it.b.a })
            assertEquals(setOf(LcncBlackboard.programKey("bad"), LcncBlackboard.programKey("occupied")), rig.element.refusals.keys)
            assertSame(occupied, assertNotNull(rig.fanout.slotOf(occupied.name)).workgroup)
            assertEquals("existing owner", assertNotNull(rig.fanout.dispatch(rig.request(name = "occupied")).completion).await())
            rig.beforeBind = { _, _, _ -> }
            rig.element.refresh()
            assertEquals(setOf("review", "bad"), rig.element.registrations.view.map { it.b.a }.toSet())
        }
    }

    @Test fun drainJoinsAdmittedUpdatesAndLeavesBorrowedHandlersAvailable() = runTest {
        fixture { rig ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            rig.beforeBind = { _, _, _ -> started.complete(Unit); release.await() }
            rig.put(program())
            val opened = async { runCatching { rig.element.open() } }
            started.await()
            val drained = async { rig.element.drain() }
            runCurrent()
            try { assertFalse(drained.isCompleted) } finally { release.complete(Unit) }
            opened.await().exceptionOrNull()?.let { assertEquals("LCNC manifestation stopped", it.message) }
            drained.await()
            assertTrue(rig.element.supervisor.isCompleted)
            assertEquals(ContentId(LcncBlackboard.cidOf(program())) to "first", assertIs<Join<*, *>>(rig.result()).pair)
            rig.put(program("after close"))
            runCurrent()
            assertEquals(1, rig.bindings.size)
            assertFailsWith<IllegalStateException> { rig.element.refresh() }
        }
    }

    @Test fun aPinnedVersionSurvivesLatestReplacementAndWithdrawal() = runTest {
        fixture { rig ->
            val first = program()
            val firstCid = ContentId(LcncBlackboard.cidOf(first))
            rig.put(first)
            rig.element.open()
            rig.put(program("latest"))
            rig.element.refresh()
            val pin = rig.element.pin(first, firstCid, Subnet.parse("local.review"))
            assertEquals("${LcncBlackboard.programKey("review")}@${firstCid.value}", pin.name)
            assertSame(pin, rig.element.pin(first, firstCid, Subnet.parse("local.review")))
            assertEquals(1, rig.element.pinnedRegistrations.size)
            assertEquals(ContentId(LcncBlackboard.cidOf(program("latest"))) to "latest", assertIs<Join<*, *>>(rig.result()).pair)
            rig.board.remove(LcncBlackboard.programKey("review"))
            rig.element.refresh()
            assertTrue(rig.element.registrations.view.none())
            assertSame(pin, rig.fanout.slotOf(pin.name)?.workgroup)
            assertEquals(firstCid to "first", assertIs<Join<*, *>>(
                assertNotNull(rig.fanout.dispatch(rig.pinnedRequest(firstCid)).completion).await()).pair)
            assertFailsWith<IllegalArgumentException> { rig.element.pin(program("wrong"), firstCid, pin.scope) }
            assertFailsWith<IllegalStateException> { rig.element.pin(first, firstCid, Subnet.local) }
        }
    }

    @Test fun recoveryYieldsThenDrainsALazyBacklogBeyondChannelCapacityAfterStop() = runTest {
        fixture { rig ->
            val capacity = Semaphore(3)
            val actual = linkedMapOf<String, CompletableDeferred<Any?>>()
            val frozen = program()
            val cid = ContentId(LcncBlackboard.cidOf(frozen))
            var produced = 0
            rig.dispatchQueued = { entry ->
                capacity.acquire()
                assertTrue(rig.element.currentPreparation(), "Recovery transfers its still-live admission authority")
                rig.element.pin(frozen, cid, Subnet.parse("local.review"))
                CompletableDeferred<Any?>().also { completion ->
                    actual[entry.a.jobId] = completion
                    completion.invokeOnCompletion { capacity.release() }
                }
            }
            rig.element.open()
            rig.element.recover(sequence {
                repeat(96) { ordinal -> produced++; yield(rig.entry("recovered-$ordinal")) }
            })
            assertEquals(0, produced, "Installing recovery must yield instead of holding attachment open")
            runCurrent()
            assertEquals(4, produced, "Only three active roots and one waiting admission are materialized")
            assertEquals(3, actual.size, "The root worker does not await one handler before admitting the next")
            rig.element.stopAdmission()
            val draining = async { rig.element.drainQueued() }
            runCurrent()
            assertFalse(draining.isCompleted)
            var completed = 0
            while (completed < 96) {
                runCurrent()
                val ready = actual.values.filter { !it.isCompleted }
                assertTrue(ready.isNotEmpty(), "The admitted backlog must continue after external stop")
                for (completion in ready) { completion.complete(Unit); completed++ }
            }
            draining.await()
            assertEquals(96, produced)
            assertEquals((0 until 96).map { "lcnc/run/recovered-$it" }, actual.keys.toList())
            assertEquals(1, rig.element.pinnedRegistrations.size)
        }
    }

    @Test fun anAdmittedPreparationTransfersAfterStopAndReturnsTheActualCompletion() = runTest {
        fixture { rig ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val actual = CompletableDeferred<Any?>()
            var captured: CoroutineContext? = null
            rig.dispatchQueued = {
                assertTrue(rig.element.currentPreparation())
                assertSame(rig.element, currentCoroutineContext()[LcncWorkgroupElement])
                actual
            }
            rig.element.open()
            val accepted = async { rig.element.prepare {
                captured = currentCoroutineContext()
                entered.complete(Unit)
                release.await()
                rig.element.queue(rig.entry("accepted"))
            } }
            entered.await()
            rig.element.stopAdmission()
            val draining = async { rig.element.drainQueued() }
            runCurrent()
            try {
                assertFalse(draining.isCompleted)
                assertFailsWith<IllegalStateException> { rig.element.prepare { error("not admitted") } }
                withContext(rig.element) { assertFalse(rig.element.currentPreparation()) }
            } finally { release.complete(Unit) }
            assertSame(actual, accepted.await())
            draining.await()
            assertFalse(actual.isCompleted, "Queue drain joins dispatch preparations, not borrowed handler results")
            withContext(assertNotNull(captured).minusKey(Job)) {
                assertFalse(rig.element.currentPreparation(), "A completed preparation cannot authorize later work")
                assertFailsWith<IllegalStateException> { rig.element.prepare { error("expired") } }
            }
            actual.complete(Unit)
        }
    }

    @Test fun aRecoveryTraversalFailureIsReportedAndReleasesItsPreparation() = runTest {
        val rig = Rig()
        val failure = IllegalStateException("Queued reference traversal failed")
        val admitted = mutableListOf<String>()
        rig.dispatchQueued = { entry -> admitted += entry.a.jobId; CompletableDeferred<Any?>(Unit) }
        rig.fanout.open()
        try {
            rig.element.open()
            rig.element.recover(sequence {
                yield(rig.entry("before-failure"))
                throw failure
            })
            runCurrent()
            assertEquals(listOf("lcnc/run/before-failure"), admitted)
            assertEquals(failure.message, assertFailsWith<IllegalStateException> { rig.element.drainQueued() }.message)
            assertEquals(failure.message, assertFailsWith<IllegalStateException> { rig.element.drain() }.message)
            assertTrue(rig.element.supervisor.isCompleted)
            assertFalse(rig.element.supervisor.children.any(), "A failed producer must release its permit and every owned stage")
        } finally { withContext(NonCancellable) {
            runCatching { rig.element.drain() }
            rig.fanout.drain()
        } }
    }

    @Test fun aLiveChildBypassesABlockedRootQueueAndCanPinDuringDrain() = runTest {
        fixture { rig ->
            val rootEntered = CompletableDeferred<Unit>()
            val releaseRoot = CompletableDeferred<Unit>()
            val parentEntered = CompletableDeferred<Unit>()
            val releaseParent = CompletableDeferred<Unit>()
            val frozen = program("child")
            val cid = ContentId(LcncBlackboard.cidOf(frozen))
            rig.dispatchQueued = { entry ->
                if (entry.a.jobId == "lcnc/run/blocked") {
                    rootEntered.complete(Unit)
                    releaseRoot.await()
                    CompletableDeferred<Any?>(Unit)
                } else {
                    assertNotNull(rig.fanout.currentInvocation())
                    rig.element.pin(frozen, cid, Subnet.parse("local.review"))
                    assertNotNull(rig.fanout.dispatch(rig.pinnedRequest(cid)).completion)
                }
            }
            rig.element.open()
            val request = nuid(Capability.Process("parent"), Nonce.RandomBytes(), Subnet.local)
            rig.fanout.register(Workgroup("parent", Subnet.local, traitSpaceOf(request.capability))) {
                parentEntered.complete(Unit)
                releaseParent.await()
                rig.element.queue(rig.entry("child", currentCoroutineContext())).await()
            }
            val blocked = async { rig.element.queue(rig.entry("blocked")) }
            rootEntered.await()
            val parent = assertNotNull(rig.fanout.dispatch(request).completion)
            parentEntered.await()
            try {
                rig.element.stopAdmission()
                rig.fanout.stopAdmission()
                releaseParent.complete(Unit)
                assertEquals(cid to "child", assertIs<Join<*, *>>(withTimeout(1_000) { parent.await() }).pair)
                assertFalse(blocked.isCompleted, "The descendant must finish without waiting for the root stage")
                assertEquals(1, rig.element.pinnedRegistrations.size)
            } finally { releaseParent.complete(Unit); releaseRoot.complete(Unit) }
            blocked.await().await()
            rig.element.drainQueued()
            rig.fanout.drain()
        }
    }
}
