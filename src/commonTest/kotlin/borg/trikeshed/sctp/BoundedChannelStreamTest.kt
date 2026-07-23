/*
 * Copyright (C) 2024 TrikeShed
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package borg.trikeshed.sctp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundedChannelStreamTest {

    @Test
    fun testEnqueueDequeue() = runTest {
        val stream = BoundedChannelStream(capacity = 2)
        assertTrue(stream.enqueue("chunk1".encodeToByteArray()))
        assertTrue(stream.enqueue("chunk2".encodeToByteArray()))

        val overflow = !stream.enqueue("chunk3".encodeToByteArray())
        assertTrue(overflow, "Should overflow")

        val chunk1 = stream.dequeue()
        assertEquals("chunk1", chunk1?.decodeToString())

        val chunk2 = stream.dequeue()
        assertEquals("chunk2", chunk2?.decodeToString())
    }
}
