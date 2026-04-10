@file:JvmName("Betanet")

package borg.literbike.betanet

/**
 * Betanet module - MLIR schema coordination, cursor patterns, and data processing.
 * Ported from literbike/src/betanet/lib.rs.
 *
 * Core modules:
 * - Baby Pandas: Economical DataFrame library with cursor operations
 * - Adaptive Typing: IoMemento + Evidence counters for automatic type inference
 * - Anchor: Protocol detection with pattern matching
 * - Detector Pipeline: SIMD-first detection with MLIR fallback
 * - Indexed: TrikeShed's zero-allocation sequence abstraction
 * - Densifier: Register-packed tuples
 * - Capabilities: Runtime capability probes
 * - Idempotent Tuples: Conflict-free data operations
 */

// Re-exports from baby_pandas
// BabyDataFrame, ColumnMeta, GroupedDataFrame, bytesToHex

// Re-exports from adaptive_typing
// IoMemento, Evidence, SIMDStrategy

// Re-exports from anchor
// Anchor, ProtocolDetector

// Re-exports from detector_pipeline
// detectWithPolicy, detectPipeline, Detection, compileMlir, interpretMlir

// Re-exports from indexed
// Join, Indexed, IndexedOps, collect, get, mapIndexed

// Re-exports from densifier
// DensifiedJoinU32Fn

// Re-exports from capabilities
// hasAvx2, hasMlir, hasEbpf

// Re-exports from idempotent_tuples
// IdempotentTuple, MetricTuple, ConfigTuple, TupleBatch, CRDTTuple
