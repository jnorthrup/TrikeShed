/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.lib

import kotlin.coroutines.CoroutineContext

/**
 * Append-only write-ahead log — expect/actual SPI.
 *
 * JVM:  Panama MemorySegment mmap, grows the file via RAF, no synchronized needed
 *       (single-threaded runBlocking loop owns the segment).
 * JS:   in-memory buffer, lost on process exit (dev mode only).
 * Native: posix mmap.
 */
interface AppendWal : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<AppendWal>

    /** Append a (key, payload) record. Returns the file offset of the write. */
    suspend fun append(key: String, payload: ByteArray): Long

    /** Replay all (key, payload) records in insertion order. */
    fun replay(): Sequence<Pair<String, ByteArray>>

    /** Close and release resources. */
    fun close()
}
