package borg.trikeshed.pointcut.polyglot

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.userspace.nio.process.ProcessResult
import borg.trikeshed.userspace.nio.process.ProcessSpec
import borg.trikeshed.userspace.nio.process.ProcessWorker
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class DummyProcessWorker : ProcessWorker {
    override suspend fun spawn(spec: ProcessSpec): ProcessResult {
        return ProcessResult(0, ByteArray(0), ByteArray(0))
    }
}

class PointcutPolyglotBlackboardTaxonomyTest {
    @Test
    fun testPointcutChildVm() = runTest {
        val taxonomy = GraalPolyglotBlackboardTaxonomy()
        val worker = DummyProcessWorker()

        val result = taxonomy.pointcutChildVm(worker, listOf("java", "-version"))
        assertNotNull(result)
    }
}
