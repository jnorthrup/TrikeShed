/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.AppendWal
import java.io.File
import java.io.RandomAccessFile
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.coroutines.CoroutineContext

/**
 * Panama MemorySegment mmap WAL — JVM actual for [AppendWal].
 *
 * File layout:
 *   header: MAGIC (4 bytes) + VERSION (4 bytes)
 *   records: keyLen(4) + keyBytes + payloadLen(4) + payloadBytes  (repeated)
 *
 * The segment is mapped with Arena AUTO so the OS manages unmapping.
 * Appends grow the file via RAF then remap — O(1) append in practice
 * because the OS pre-extends the file under mmap.
 *
 * CoroutineContext.Element so it registers into the CCEK scope alongside
 * FlywheelElement and other reactor elements.
 */
class JvmAppendWal(private val path: File) : AppendWal {
    companion object {
        private const val MAGIC = 0xCA05A101
        private const val VERSION = 1
        private val OFF = ValueLayout.JAVA_LONG_UNALIGNED
        private val O4 = ValueLayout.JAVA_INT_UNALIGNED
    }

    override val key: CoroutineContext.Key<*> get() = AppendWal

    private var raf = RandomAccessFile(path, "rw").also { initHeader(it) }
    private var segment: MemorySegment = mapSegment(raf, path.length())

    private fun initHeader(raf: RandomAccessFile) {
        if (path.length() < 8L) {
            raf.writeInt(MAGIC)
            raf.writeInt(VERSION)
            raf.fd.sync()
        }
    }

    private fun mapSegment(raf: RandomAccessFile, len: Long): MemorySegment {
        val channel = FileChannel.open(path.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)
        return channel.map(Arena.ofAuto(), 0, len)
    }

    override suspend fun append(key: String, payload: ByteArray): Long = runBlocking@{
        val keyBytes = key.encodeToByteArray()
        val keyLen = keyBytes.size
        val payloadLen = payload.size
        val recordLen = 4 + keyLen + 4 + payloadLen

        // Grow file first (OS pre-extends under mmap efficiently)
        val offset = raf.length()
        raf.setLength(offset + recordLen)

        // Sync old segment + header
        raf.fd.sync()

        // Remap to cover the new length
        segment = mapSegment(raf, raf.length())

        // Write record into the mapped segment
        val base = offset
        segment.set(O4, base, keyLen.toLong())
        segment.asByteBuffer().position(base.toInt() + 4).put(keyBytes)
        val payloadBase = base + 4 + keyLen
        segment.set(O4, payloadBase, payloadLen.toLong())
        segment.asByteBuffer().position(payloadBase.toInt() + 4).put(payload)

        // Sync the file so OS flushes the mmap pages
        raf.fd.sync()
        offset
    }

    override fun replay(): Sequence<Pair<String, ByteArray>> = sequence {
        val len = path.length()
        if (len < 8L) return@sequence

        // Read fresh each replay to catch external appends
        val readRaf = RandomAccessFile(path, "r")
        try {
            val magic = readRaf.readInt()
            val version = readRaf.readInt()
            if (magic != MAGIC || version != VERSION) error("Invalid WAL header: magic=$magic version=$version")

            val channel = FileChannel.open(path.toPath(), StandardOpenOption.READ)
            val seg = channel.map(Arena.ofAuto(), 0, len)

            var pos = 8L
            while (pos < len) {
                val keyLen = seg.get(O4, pos).toInt()
                pos += 4
                val keyBytes = ByteArray(keyLen)
                seg.asByteBuffer().position(pos.toInt()).get(keyBytes)
                pos += keyLen

                val payloadLen = seg.get(O4, pos).toInt()
                pos += 4
                val payloadBytes = ByteArray(payloadLen)
                seg.asByteBuffer().position(pos.toInt()).get(payloadBytes)
                pos += payloadLen

                yield(keyBytes.decodeToString() to payloadBytes)
            }
        } finally {
            readRaf.close()
        }
    }

    override fun close() {
        raf.close()
    }
}
