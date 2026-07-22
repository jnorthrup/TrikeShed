/*
 * Copyright (c) 2017 TrikeShed Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package borg.trikeshed.userspace.volume

import borg.trikeshed.lib.Closeable

actual class LiburingVolume actual constructor(
    val path: String,
    actual override val blockSize: Int,
    capacityBytes: Long
) : Volume, Closeable {

    private val posixVolume = PosixVolume(path, blockSize, capacityBytes)

    actual override val capacity: Long = posixVolume.capacity

    actual override suspend fun read(lba: Long, count: Int): ByteArray {
        return posixVolume.read(lba, count)
    }

    actual override suspend fun write(lba: Long, data: ByteArray) {
        posixVolume.write(lba, data)
    }

    actual override suspend fun sync() {
        posixVolume.sync()
    }
    
    actual override fun close() {
        posixVolume.close()
    }

    actual override suspend fun submitBatch(requests: List<IoRequest>): List<IoResult> = emptyList()
    actual override fun enqueue_burst(requests: List<IoRequest>) {}
    actual override suspend fun dequeue_burst(): List<IoResult> = emptyList()
}
