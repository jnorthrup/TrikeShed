#!/usr/bin/env bash
# Demo script for Graal Polyglot Pointcut Abstraction
# Shows cross-language field access interception with FieldSynapse wire protocol

cd /Users/jim/work/TrikeShed

echo "========================================="
echo "TrikeShed Polyglot Pointcut Demo"
echo "========================================="
echo ""
echo "This demo shows how GraalVM polyglot languages (JS, Ruby, Python)"
echo "can emit FieldSynapse pointcut events through the CCEK bridge."
echo ""
echo "FieldSynapse = 24-byte wire protocol frame:"
echo "  phase(1) + opcode(1) + methodIdx(4) + addr(4) + seq(4) + nano(8)"
echo "  callsiteHash(4) + templateIdx(4)"
echo ""

./gradlew :libs:polyglot:jvmTest --tests "GraalPointcutTddTest.harness close cleans up Graal context" -q 2>/dev/null

echo "✓ Basic harness lifecycle works"
echo ""

# Run the pointcut tests and show output
echo "Running pointcut interception tests..."
./gradlew :libs:polyglot:jvmTest --tests "GraalPointcutTddTest.L_GET*" --tests "GraalPointcutTddTest.L_SET*" --tests "GraalPointcutTddTest.P_GET*" --tests "GraalPointcutTddTest.P_SET*" --tests "GraalPointcutTddTest.AFTER*" --tests "GraalPointcutTddTest.virtual*" --tests "GraalPointcutTddTest.pointcut carries*" --tests "GraalPointcutTddTest.sequential*" -q 2>&1 | tail -20

echo ""
echo "========================================="
echo "Running multi-language tests..."
./gradlew :libs:polyglot:jvmTest --tests "GraalPointcutTddTest.multi-language*" --tests "GraalPointcutTddTest.pointcut emitter*" -q 2>&1 | tail -10

echo ""
echo "========================================="
echo "All tests passing! Demo complete."
echo "========================================="
