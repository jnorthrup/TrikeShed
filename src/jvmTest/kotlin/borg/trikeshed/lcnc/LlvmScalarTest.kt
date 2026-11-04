package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.kernel.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.nio.file.spi.*
import kotlinx.coroutines.*
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.ByteSequence
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LlvmScalarTest {
    companion object {
        fun bytes(): ByteArray = checkNotNull(LlvmScalarTest::class.java.getResourceAsStream("/sulong/add.bc")).use { it.readAllBytes() }
        fun files(bytes: ByteArray): FileOperations {
            val memory = InMemoryFileOperations().also { it.write("/operator/add.bc", bytes) }
            return object : FileOperations by memory, BoundedFileReader {
                override fun readBounded(path: String, maxBytes: Int): ByteArray = memory.readAllBytes(path).also {
                    if (it.size > maxBytes) throw BoundedReadLimitException(maxBytes)
                }
            }
        }
        fun profile(bytes: ByteArray = bytes(), export: String = "add") =
            LlvmScalarProfile(Path.of("/operator/add.bc"), ContentId.of(bytes).hex, export)
    }

    @Test fun verifiedBinaryExecutesAndCopiesHostScalars(): Unit = runBlocking {
        val kernel = KernelElement(coroutineContext + Dispatchers.Default, KernelId("llvm-test"))
        try {
            val evaluator = LlvmScalar.open(kernel, KernelId("scalar"), profile(), files(bytes()))
            val invocation = LcncInvocationLedger.Invocation(mutableMapOf())
            val result = withContext(invocation) { evaluator.evaluate(19, 23) }
            assertEquals(42, result["value"])
            assertEquals(-2, evaluator.evaluate(-5, 3)["value"])
            assertEquals(0, evaluator.evaluate(0, 0)["value"])
            assertEquals(Int.MIN_VALUE, evaluator.evaluate(Int.MAX_VALUE, 1)["value"])
            val runtime = invocation.values["runtime"] as Map<*, *>
            assertEquals(ContentId.of(bytes()).value, runtime["moduleCid"])
            assertEquals(true, runtime["verifyBitcode"])
            assertEquals(true, runtime["nativeAccess"])
            assertEquals("unobserved", runtime["internalProgress"])
            assertTrue((runtime["queueNanos"] as Long) >= 0)
            assertTrue((runtime["callNanos"] as Long) >= 0)
        } finally { kernel.drain() }
    }

    @Test fun nativeDeniedControlStillRefusesLlvm() {
        val failure = assertFails {
            Context.newBuilder("llvm").allowNativeAccess(false).build().use { context ->
                context.eval(Source.newBuilder("llvm", ByteSequence.create(bytes()), "add.bc").build())
            }
        }
        assertTrue(failure.message.orEmpty().contains("native", ignoreCase = true), failure.toString())
    }

    @Test fun invalidModuleOpenReleasesSeatForRetry(): Unit = runBlocking {
        val kernel = KernelElement(coroutineContext + Dispatchers.Default, KernelId("llvm-test"))
        try {
            assertFails { LlvmScalar.open(kernel, KernelId("scalar"), profile(), files(_a[0.toByte()])) }
            assertFails { LlvmScalar.open(kernel, KernelId("scalar"), profile(export = "missing"), files(bytes())) }
            val malformed = _a[0x42.toByte(), 0x43.toByte(), 0xc0.toByte(), 0xde.toByte(), 0.toByte()]
            assertFails { LlvmScalar.open(kernel, KernelId("scalar"), profile(malformed), files(malformed)) }
            assertEquals(0, kernel.seats().size)
            assertEquals(42, LlvmScalar.open(kernel, KernelId("scalar"), profile(), files(bytes())).evaluate(20, 22)["value"])
        } finally { kernel.drain() }
    }

    @Test fun drainSettlesAcceptedQueueBeforeClosingContext(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val completions = mutableListOf<KernelReceipt>()
        val closed = CompletableDeferred<Unit>()
        val pointcut = object : KernelPointcut {
            override fun dispatch(turn: KernelTurn) {
                if (turn.name == "llvm.call" && !entered.isCompleted) {
                    entered.complete(Unit)
                    check(release.await(15, TimeUnit.SECONDS))
                }
            }
            override fun complete(receipt: KernelReceipt) { completions.add(receipt) }
            override fun close() { closed.complete(Unit) }
        }
        val kernel = KernelElement(coroutineContext + Dispatchers.Default, KernelId("llvm-test"))
        try {
            val evaluator = LlvmScalar.open(kernel, KernelId("scalar"), profile(), files(bytes()), pointcut)
            val first = evaluator.submit(19, 23)
            withTimeout(15_000) { entered.await() }
            val second = evaluator.submit(-5, 3)
            val drain = async(start = CoroutineStart.UNDISPATCHED) { evaluator.drain() }
            assertFails { evaluator.submit(0, 0) }
            assertFalse(drain.isCompleted)
            assertFalse(closed.isCompleted)
            release.countDown()
            assertEquals(42, withTimeout(15_000) { first.await().getOrThrow()["value"] })
            assertEquals(-2, second.await().getOrThrow()["value"])
            withTimeout(15_000) { drain.await() }
            assertTrue(closed.isCompleted)
            assertEquals(2, completions.count { it.turn.name == "llvm.call" && it.outcome == KernelOutcome.SUCCESS })
            assertFails { evaluator.evaluate(0, 0) }
            assertEquals(0, kernel.seats().size)
        } finally { release.countDown(); kernel.drain() }
    }

    @Test fun runnerRejectsAuthorityAndNonI32InputsThenRecovers(): Unit = runBlocking {
        val kernel = KernelElement(coroutineContext + Dispatchers.Default, KernelId("llvm-test"))
        try {
            val runner = LlvmScalar.open(kernel, KernelId("scalar"), profile(), files(bytes())).runner()
            val node = LcncNode("sum", LlvmScalarContract.TYPE, _m["left" j "19", "right" j "23"])
            for (value in s_.get<Any?>(null, 1.5, Double.NaN, 2147483648L, "1e2", true).view)
                assertFails { runner.execute(node, _m["left" j value]) }
            assertFails { runner.execute(node.copy(params = node.params + _m["module" j "/arbitrary.bc"]), emptyMap()) }
            assertFails { runner.execute(node, _m["export" j "arbitrary"]) }
            assertFails { runner.execute(node, _m["left" j 1, "left?" j 2]) }
            assertEquals(42, runner.execute(node, _m["left?" j 20, "right?" j 22])["value"])
            assertEquals(42, runner.execute(node, emptyMap())["value"])
        } finally { kernel.drain() }
    }

    @Test fun profileIsStrictOperatorConfiguration() {
        val fields = _m["module" j "/operator/add.bc", "sha256" j ContentId.of(bytes()).hex,
            "export" j "add", "abi" j "i32(i32,i32)"]
        assertEquals("add", LlvmScalarProfile.parse(jsonOf(fields)).export)
        for (extra in s_["environment", "code", "nativeAccess", "verifyBitcode"].view)
            assertFails { LlvmScalarProfile.parse(jsonOf(fields + _m[extra j "override"])) }
        assertFails { LlvmScalarProfile.parse(jsonOf(fields + _m["module" j "relative.bc"])) }
        assertFails { LlvmScalarProfile.parse(jsonOf(fields + _m["abi" j "pointer"])) }
        assertFails { LlvmScalarProfile.parse(jsonOf(fields).dropLast(1) + ",\"export\":\"other\"}") }
    }
}
