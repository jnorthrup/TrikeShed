package borg.trikeshed.polyglot.graal

import borg.trikeshed.lib.Series
import borg.trikeshed.polyglot.ccek.FieldSynapse
import borg.trikeshed.polyglot.ccek.PointcutEventProducer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TDD RED tests for verifying TruffleInstrument fires on real GraalPy attribute access.
 * 
 * These tests prove whether the VM-level instrument actually captures pointcuts
 * when Python code executes attribute reads/writes (not just manual emit calls).
 * 
 * If these tests fail, it means GraalPy's AST nodes don't tag variable reads/writes
 * with StandardTags.ReadVariableTag/WriteVariableTag automatically - we'd need
 * additional instrumentation to intercept at the Python level.
 */
class PythonPointcutAutoInstrumentTest {

    private lateinit var harness: GraalPointcutHarness
    private lateinit var capture: Capture

    @BeforeEach
    fun setUp() {
        capture = Capture()
        harness = GraalPointcutHarness(pointcutProducer = capture.producer)
    }

    @AfterEach
    fun tearDown() {
        if (::harness.isInitialized) harness.close()
    }

    /**
     * Test 1: Python instance attribute READ should trigger L_GET pointcut
     * via VM-level TruffleInstrument (not manual emit).
     * 
     * RED: If GraalPy doesn't tag variable reads, capture.synapses will be empty.
     */
    @Test
    fun `python instance attribute read triggers L_GET via VM instrument`() {
        try {
            harness.eval("python", """
                class TestClass:
                    def __init__(self):
                        self.value = 42
                obj = TestClass()
                x = obj.value
                print(x)
            """.trimIndent())
            // Filter for L_GET events (opcode 0xA5)
            val lGetCount = capture.synapses.count { it.opcode == 0xA5.toByte() }
            assertTrue(lGetCount > 0,
                "Expected VM-level L_GET pointcuts from 'obj.value' read, got $lGetCount")
        } catch (e: Exception) {
            // Python not available - skip
            assumePythonAvailable()
        }
    }

    /**
     * Test 2: Python instance attribute WRITE should trigger L_SET pointcut
     * via VM-level TruffleInstrument.
     * 
     * RED: If GraalPy doesn't tag variable writes, capture.synapses will be empty.
     */
    @Test
    fun `python instance attribute write triggers L_SET via VM instrument`() {
        try {
            harness.eval("python", """
                class TestClass:
                    def __init__(self):
                        self.value = 42
                obj = TestClass()
                obj.value = 100
            """.trimIndent())
            val lSetCount = capture.synapses.count { it.opcode == 0xA6.toByte() }
            assertTrue(lSetCount > 0,
                "Expected VM-level L_SET pointcuts from 'obj.value = 100' write, got $lSetCount")
        } catch (e: Exception) {
            assumePythonAvailable()
        }
    }

    /**
     * Test 3: Python class attribute READ should trigger P_GET pointcut.
     */
    @Test
    fun `python class attribute read triggers P_GET via VM instrument`() {
        try {
            harness.eval("python", """
                class TestClass:
                    class_attr = 'hello'
                x = TestClass.class_attr
                print(x)
            """.trimIndent())
            val pGetCount = capture.synapses.count { it.opcode == 0xA7.toByte() }
            assertTrue(pGetCount > 0,
                "Expected VM-level P_GET pointcuts from class attribute read, got $pGetCount")
        } catch (e: Exception) {
            assumePythonAvailable()
        }
    }

    /**
     * Test 4: Python local variable READ should trigger L_GET pointcut.
     */
    @Test
    fun `python local variable read triggers L_GET via VM instrument`() {
        try {
            harness.eval("python", """
                x = 42
                y = x
                print(y)
            """.trimIndent())
            val lGetCount = capture.synapses.count { it.opcode == 0xA5.toByte() }
            assertTrue(lGetCount > 0,
                "Expected VM-level L_GET from local variable read, got $lGetCount")
        } catch (e: Exception) {
            assumePythonAvailable()
        }
    }

    /**
     * Test 5: VM-level instrument fires BEFORE/AFTER phases for read.
     * Verifies the dual-phase emission contract.
     */
    @Test
    fun `python read emits BEFORE and AFTER phases via VM instrument`() {
        try {
            harness.eval("python", """
                x = 42
                y = x
            """.trimIndent())
            val beforeReads = capture.synapses.count {
                it.opcode == 0xA5.toByte() && it.phase == 0.toByte()
            }
            val afterReads = capture.synapses.count {
                it.opcode == 0xA5.toByte() && it.phase == 1.toByte()
            }
            assertTrue(beforeReads > 0,
                "Expected BEFORE L_GET phases, got $beforeReads")
            assertTrue(afterReads > 0,
                "Expected AFTER L_GET phases, got $afterReads")
        } catch (e: Exception) {
            assumePythonAvailable()
        }
    }

    /**
     * Helper: Skip test if Python is not available.
     */
    private fun assumePythonAvailable() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            capture.synapses.isNotEmpty() || tryPythonBasic(),
            "Python not available in this Graal distribution"
        )
    }

    private fun tryPythonBasic(): Boolean {
        return try {
            harness.eval("python", "1+1")
            true
        } catch (e: Exception) {
            false
        }
    }

    private class Capture {
        val synapses: CopyOnWriteArrayList<FieldSynapse> = CopyOnWriteArrayList()

        val producer: PointcutEventProducer = object : PointcutEventProducer {
            override fun emit(synapse: FieldSynapse) {
                synapses.add(synapse)
            }
            override fun emitBatch(synapses: Series<FieldSynapse>) {}
        }
    }
}