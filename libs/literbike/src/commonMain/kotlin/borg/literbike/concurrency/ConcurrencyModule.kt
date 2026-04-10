/**
 * Structured Concurrency for Literbike
 *
 * Based on Kotlin Coroutines patterns:
 * - CoroutineContext.Element composition (CCEK pattern)
 * - Channel-based communication
 * - Flow-based reactive streams
 * - Structured concurrency scopes
 *
 * Integration with Kotlin Coroutines:
 * - Channels: Use kotlinx.coroutines.channels for production
 * - Flows: Use kotlinx.coroutines.flow for Flow-like patterns
 * - Scopes: Use CoroutineScope with CCEK context
 */

package borg.literbike.concurrency

// Re-export all concurrency types
typealias CoroutineResult<T> = Result<T>
