package borg.trikeshed.narsese

import borg.trikeshed.graal.ConfixBlackboard
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class CurationLifecycleTest {
    @Test fun familyDrainRejectsRetainedProviderAdmissionBeforeRootWorkFinishes() = runBlocking {
        withTimeout(10_000) {
            val directory = java.nio.file.Files.createTempDirectory("curation-family").toFile()
            val nodes = ConstellationNodes(directory, ConfixBlackboard(), scope = this)
            val selected = nodes.forProvider(DecisionProvider.OPENAI)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            try {
                nodes.curation.submit { entered.complete(Unit); release.await() }
                entered.await()
                val draining = async(start = CoroutineStart.UNDISPATCHED) { nodes.drain() }
                while (!selected.curation.input.isClosed()) yield()
                assertFalse(draining.isCompleted)
                val failure = assertFailsWith<IllegalStateException> {
                    selected.admitScore("fixture", emptyList(), coroutineContext)
                }
                assertEquals("Curation is draining", failure.message)
                assertFailsWith<IllegalStateException> {
                    selected.asked("fixture", mapOf("page" to "page"), emptyMap())
                }
                release.complete(Unit)
                draining.await()
            } finally { release.complete(Unit); nodes.drain(); directory.deleteRecursively() }
        }
    }

    @Test fun parentCancellationJoinsActiveAndQueuedWorkIncludingNestedAnswers() = runBlocking {
        withTimeout(10_000) {
            val parent = Job()
            val owner = CurationElement(CoroutineScope(coroutineContext + parent), capacity = 1, answerWorkers = 1)
            val entered = CompletableDeferred<Unit>()
            val model = CompletableDeferred<Unit>()
            val answering = CompletableDeferred<Unit>()
            val answer = CompletableDeferred<Unit>()
            val effects = mutableListOf<String>()
            try {
                val active = owner.submit {
                    entered.complete(Unit); model.await()
                    effects.add("active")
                    owner.answer("active") {
                        answering.complete(Unit); answer.await()
                        effects.add("active answer"); mapOf("done" to true)
                    }
                }
                entered.await()
                val queued = owner.submit {
                    owner.answer("queued") { effects.add("queued answer"); mapOf("done" to true) }
                    effects.add("queued")
                }
                parent.cancel()
                val joined = async { parent.join() }
                val drained = async { owner.drain() }
                while (!owner.input.isClosed()) yield()
                assertFalse(active.isCompleted, "Parent cancellation must preserve the accepted result record")
                assertFalse(queued.isCompleted)
                assertFalse(parent.isCompleted, "The parent must retain ownership until its curation effects settle")
                assertFailsWith<IllegalStateException> { owner.answer("external") { error("unadmitted answer ran") } }
                model.complete(Unit)
                answering.await()
                assertFalse(joined.isCompleted)
                assertFalse(drained.isCompleted)
                answer.complete(Unit)
                active.await().getOrThrow(); queued.await().getOrThrow()
                joined.await(); drained.await()
                assertEquals(listOf("active", "active answer", "queued answer", "queued"), effects)
                assertTrue(owner.supervisor.isCompleted)
                assertTrue(owner.claims.isEmpty())
                assertTrue(owner.states.isEmpty())
            } finally {
                model.complete(Unit); answer.complete(Unit)
                owner.drain(); parent.cancelAndJoin()
            }
        }
    }

    @Test fun drainClosesAdmissionAndFinishesQueuedScoreAndItsAnswers(): Unit = runBlocking {
        withTimeout(10_000) {
            val owner = CurationElement(this, capacity = 1, answerWorkers = 1)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val answering = CompletableDeferred<Unit>()
            val answer = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val restore = owner.submit {
                entered.complete(Unit); release.await(); order.add("restore")
            }
            entered.await()
            val score = owner.submit {
                owner.answer("score") { answering.complete(Unit); answer.await(); mapOf("done" to true) }
                order.add("score")
            }
            val rejected = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { owner.submit { error("unadmitted probe ran") } }
            }
            assertFalse(rejected.isCompleted, "A full admission queue must suspend its sender")
            val drain = async { owner.drain() }
            while (!owner.input.isClosed()) yield()
            assertFalse(drain.isCompleted)
            release.complete(Unit)
            answering.await()
            assertTrue(rejected.await().isFailure)
            assertFalse(drain.isCompleted, "Drain must wait for accepted score answers")
            answer.complete(Unit)
            drain.await()
            restore.await().getOrThrow(); score.await().getOrThrow()
            assertEquals(listOf("restore", "score"), order)
            assertTrue(owner.supervisor.isCompleted)
            assertFailsWith<IllegalStateException> { owner.submit {} }
        }
    }

    @Test fun cancelledWaiterDoesNotCancelSharedAnswerAndFailedClaimsCanRetry() = runBlocking {
        withTimeout(10_000) {
            val owner = CurationElement(this, capacity = 2, answerWorkers = 2)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val first = async {
                owner.answer("same", "state") {
                    calls.incrementAndGet(); entered.complete(Unit); release.await(); mapOf("noul" to .9)
                }
            }
            entered.await()
            val follower = async(start = CoroutineStart.UNDISPATCHED) {
                owner.answer("same", "state") { error("duplicate paid request") }
            }
            first.cancelAndJoin()
            release.complete(Unit)
            assertEquals(.9, follower.await()["noul"])
            assertEquals(1, calls.get())

            val failed = CompletableDeferred<Unit>()
            val fail = CompletableDeferred<Unit>()
            val failure = IllegalArgumentException("decision refused")
            val leader = async {
                runCatching { owner.answer("failure") { calls.incrementAndGet(); failed.complete(Unit); fail.await(); throw failure } }
            }
            failed.await()
            val peer = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { owner.answer("failure") { error("duplicate failed request") } }
            }
            fail.complete(Unit)
            assertSame(failure, generateSequence(assertNotNull(leader.await().exceptionOrNull())) { it.cause }.last())
            assertSame(failure, generateSequence(assertNotNull(peer.await().exceptionOrNull())) { it.cause }.last())
            assertEquals(true, owner.answer("failure") { calls.incrementAndGet(); mapOf("retry" to true) }["retry"])
            owner.drain()
            assertEquals(3, calls.get())
            assertTrue(owner.claims.isEmpty())
            assertTrue(owner.states.isEmpty())
        }
    }

    @Test fun boundedParserAndWindowWorkersFanInEveryAdmittedResult() = runBlocking {
        withTimeout(10_000) {
            val owner = CurationElement(this, capacity = 2, answerWorkers = 2)
            val release = CompletableDeferred<Unit>()
            val full = CompletableDeferred<Unit>()
            val parsed = AtomicInteger()
            val processing = AtomicInteger()
            val windowCount = AtomicInteger()
            val collected = mutableSetOf<Int>()
            val sections = owner.submit {
                owner.sections<Int, Int, Int>(
                    source = { send -> for (i in 0 until 100) send(i) },
                    parse = { parsed.incrementAndGet(); it },
                    table = { section ->
                        if (processing.incrementAndGet() == 2) full.complete(Unit)
                        release.await()
                        owner.windows(25, ask = { it }, collect = { _, _ -> windowCount.incrementAndGet() }, workers = 2)
                        section
                    },
                    collect = { collected.add(it) }, workers = 2, capacity = 1,
                )
            }
            full.await()
            // Two workers, one queued parse and one parser blocked handing off the next section.
            assertTrue(parsed.get() <= 4, "Parsing must stop behind the bounded model queue")
            release.complete(Unit)
            sections.await().getOrThrow()
            assertEquals((0 until 100).toSet(), collected)
            assertEquals(2_500, windowCount.get())
            owner.drain()
        }
    }
}
