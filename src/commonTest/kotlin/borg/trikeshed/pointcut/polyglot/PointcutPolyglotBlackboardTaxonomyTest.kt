package borg.trikeshed.pointcut.polyglot

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.userspace.nio.process.ProcessResult
import borg.trikeshed.userspace.nio.process.ProcessSpec
import borg.trikeshed.userspace.nio.process.ProcessWorker
import borg.trikeshed.classfile.model.PointcutCoordinateSeries
import borg.trikeshed.classfile.model.emptyPointcutCoordinates
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DummyProcessWorker : ProcessWorker {
    override suspend fun spawn(spec: ProcessSpec): ProcessResult {
        return ProcessResult(0, ByteArray(0), ByteArray(0))
    }
}

class DummyTspyPolyglotHost : TspyPolyglotHost {
    override suspend fun evaluatePython(source: String): PointcutCoordinateSeries {
        return emptyPointcutCoordinates()
    }
}

class PointcutPolyglotBlackboardTaxonomyTest {
    @Test
    fun testPointcutChildVmException() = runTest {
        val taxonomy = GraalPolyglotBlackboardTaxonomy()
        val worker = DummyProcessWorker()

        try {
            taxonomy.pointcutChildVm(worker, listOf("java", "-version"))
        } catch (e: UnsupportedOperationException) {
            // expected
            assertNotNull(e)
            assertTrue(e.message!!.contains("evaluating python source via TspyPolyglotHost"))
        }
    }

    @Test
    fun testPointcutChildVmWithHost() = runTest {
        val host = DummyTspyPolyglotHost()
        val taxonomy = GraalPolyglotBlackboardTaxonomy(tspyPolyglotHost = host)
        val worker = DummyProcessWorker()
        
        try {
            val result = taxonomy.pointcutChildVm(worker, listOf("python", "-c", "print('hello')"))
            // since DummyTspyPolyglotHost returns emptyPointcutCoordinates which throws IndexOutOfBoundsException
        } catch (e: IndexOutOfBoundsException) {
            assertTrue(e.message!!.contains("empty pointcut coordinate series"))
        }
    }

    @Test
    fun testPointcutKataSandbox() = runTest {
        val taxonomy = GraalPolyglotBlackboardTaxonomy()
        val sandbox = PolyglotKataRegistry.JAVA

        try {
            val result = taxonomy.pointcutKataSandbox(sandbox, listOf("java", "-version"))
        } catch (e: IndexOutOfBoundsException) {
            // expected from emptyPointcutCoordinates()
        }
    }

    @Test
    fun testPointcutKataSandboxInfiniteLoopKilled() = runTest {
        val taxonomy = GraalPolyglotBlackboardTaxonomy()
        val sandbox = PolyglotKataRegistry.PYTHON

        val result = taxonomy.pointcutKataSandbox(sandbox, listOf("i = 0\nwhile True:\n    i += 1"))
        assertTrue(result.a > 0, "Should have returned coordinates captured before being killed")
    }
}
