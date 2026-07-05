@file:Suppress("NOTHING_TO_INLINE", "NonAsciiCharacters")

package borg.trikeshed.asclepius

/**
 * Asclepius — pointcut GraalVM harness for a Hermes fork.
 *
 * The library is intentionally a JVM-side staging ground under libs/:
 * - GraalVM polyglot pointcuts emit the stable FieldSynapse wire shape.
 * - TrikeShed Cursor/Series is the Arrow/Feather-isomorphic analytical row shape.
 * - CCEK SupervisorContext owns effect lifecycles: Graal isolates, SQLite WAL, Arrow slabs.
 * - Confix taxonomy/blackboard is the central routing fabric for pointcuts and CRMS handles.
 */
object Asclepius
