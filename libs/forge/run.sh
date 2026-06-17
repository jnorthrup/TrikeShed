#!/usr/bin/env bash
# Forge Demo

set -e

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  FORGE DEMO - Autonomous LLM Workflow Fabric                 ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo

echo "Running Forge test suite (complete end-to-end demo)..."
echo

cd /Users/jim/work/TrikeShed

# Run tests which serve as the complete demo
./gradlew :libs:forge:jvmTest --rerun-tasks --no-daemon -q 2>&1

echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Forge Test Suite = Complete Demo"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo
echo "The test suite exercises ALL Forge capabilities:"
echo "  ✓ File storage with search & streaming"
echo "  ✓ Git-like snapshots (create, diff, branch, merge, restore)"
echo "  ✓ Prompt library with parameters"
echo "  ✓ Typed workflows (LLM, code, agent, transform, conditional, parallel)"
echo "  ✓ Sync + streaming execution with audit trail"
echo "  ✓ Artifacts with export/import (JSON)"
echo "  ✓ Real-time collaboration (cursors, presence, events)"
echo "  ✓ Snapshot branching for parallel workstreams"
echo
echo "Example workflows in examples/:"
echo "  • document-processing-pipeline.json - Ingest, extract, enhance, transform, parallelize, quality-gate, artifact"
echo
echo "To integrate LCNC:"
echo "  1. Implement ForgeWorkspace over Miniduck/ISAM + websocket sync"
echo "  2. Implement ForgeStepRunner over LCNC runtime"
echo "  3. Add AgentType.LCNC to ForgeAgentRunner"
echo
echo "See README.md for architecture details"