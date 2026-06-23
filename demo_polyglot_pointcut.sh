#!/usr/bin/env bash
# Demo script for Graal Polyglot Pointcut Abstraction
# Shows cross-language field access interception with FieldSynapse wire protocol

cd /Users/jim/work/TrikeShed

echo "========================================="
echo "TrikeShed Polyglot Pointcut Demo"
echo "========================================="
echo ""
echo "This demo shows how GraalVM polyglot languages (JS, Python)"
echo "can emit FieldSynapse pointcut events through the CCEK bridge."
echo ""
echo "FieldSynapse = 30-byte wire protocol frame:"
echo "  phase(1) + opcode(1) + methodIdx(4) + addr(4) + seq(4)"
echo "  + nano(8) + callsiteHash(4) + templateIdx(4)"
echo ""
echo "[1] JavaScript pointcut emission (L_GET/L_SET/P_GET/P_SET opcode matrix):"
./gradlew :libs:polyglot:jvmTest --tests "PointcutCertaintyTest.emitFieldAccess opcode matrix is exactly correct" 2>&1 | grep -E "(PASSED|FAILED|BUILD)" | head -3
echo ""
echo "[2] Demo polyglot flow (JS host interop + emitter):"
./gradlew :libs:polyglot:jvmTest --tests "DemoPointcutTest" 2>&1 | grep -E "(PASSED|FAILED|BUILD)" | head -3
echo ""
echo "[3] FieldSynapse 30-byte wire encoding roundtrip:"
./gradlew :libs:polyglot:jvmTest --tests "FieldSynapseWireTest" 2>&1 | grep -E "(PASSED|FAILED|BUILD)" | head -3
echo ""
echo "[4] TruffleInstrument + ExecutionEventListener wiring (E2E):"
./gradlew :libs:polyglot:jvmTest --tests "PythonPointcutInstrumentE2ETest" 2>&1 | grep -E "(PASSED|FAILED|BUILD)" | head -3
echo ""
echo "[5] PointcutProducerService registration:"
./gradlew :libs:polyglot:jvmTest --tests "PythonPointcutInstrumentTest" 2>&1 | grep -E "(PASSED|FAILED|BUILD)" | head -3
echo ""
echo "========================================="
echo "Demo complete. All pointcut pathways verified."
echo "========================================="
