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

expect class LiburingVolume(
    path: String,
    blockSize: Int = 4096,
    capacityBytes: Long = blockSize.toLong() * 1024L
) : Volume, Closeable {
    override val blockSize: Int
    override val capacity: Long
    override suspend fun read(lba: Long, count: Int): ByteArray
    override suspend fun write(lba: Long, data: ByteArray)
    override suspend fun sync()
    override fun close()

    override suspend fun submitBatch(requests: List<IoRequest>): List<IoResult>
    override fun enqueue_burst(requests: List<IoRequest>)
    override suspend fun dequeue_burst(): List<IoResult>
}
