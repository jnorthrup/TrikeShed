@file:Suppress("unused")

package borg.trikeshed.og1

import borg.trikeshed.og1.fanout.poolInit

/* OG1 — CRMS algebra embedded in TrikeShed
 *
 * libs/og1/ is the Hermes plugin host for the CRMS microkernel.
 * All Python payloads live in a wireproto StringPool (WirePool) —
 * constant-sized index references, decoded on demand by PyEngine.
 * No inline triple-quoted strings, no const val templates.
 *
 * Modules:
 *   fanout/FanoutPlan.kt     — FanoutPlan, Story, DeliveryRound, CriticVerdict,
 *                              WirePool (string pool), Payloads (wire keys)
 *   state/CrmsState.kt       — CrmsState FSM, CrmsPhase, VoterFacet, QuorumState
 *   shape/ShapeCursor.kt     — Shape, ShapeCursor, ShapeCursorBox, Blackboard,
 *                              CascadeBlackboard, ShapeToCursor, ShapeSchema, EigenResult
 *   cron/CrmsCron.kt         — CrmsCron (FSM-driven cron job runner)
 *   shape/RingSeries.kt      — RingSeries (zero-GC shim-reset event ring)
 *   shape/RealtimePipeline.kt— RealtimePipeline (most realtime path)
 *   types/CrmsTypes.kt       — VerdictKind, Branch, BrainstormState, validateStoryDependencies
 *   repl/PyEngine.kt         — PyEngine interface, PyOutcome, PyEngineKind
 *
 * Usage: `poolInit` triggers lazy pool registration on first access.
 */

val OG1_VERSION = "0.1.0-SNAPSHOT"

/** Initialize the wireproto string pool. Call once or rely on lazy init. */
fun initOg1() { poolInit }