package nio.ebpf.streaming

import nio.ebpf.algebra.EbpfInstruction
import nio.ebpf.algebra.Reg
import kotlin.test.Test
import kotlin.test.assertTrue

class EbpfJitEngineTest {
    @Test
    fun `build and verify simple add program`() {
        val engine = EbpfJitEngine("test-add")
        engine
            .movImm(10, Reg.R0)
            .addImm(32, Reg.R0)
            .jmpExit()

        val result = engine.verify()
        assertTrue(result is nio.ebpf.verifier.VerifierResult.Success)
    }
}
