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
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.fail

class SctpBoundedStreamTest {

    @Test
    fun testEnqueueAndDequeue() = runTest {
        val stream = SctpBoundedStream(capacity = 10)

        val testData1 = byteArrayOf(1, 2, 3)
        val testData2 = byteArrayOf(4, 5)

        stream.enqueue(testData1)
        stream.enqueue(testData2)

        val dequeuedData1 = stream.dequeue()
        val dequeuedData2 = stream.dequeue()

        assertTrue(testData1.contentEquals(dequeuedData1), "First dequeued data should match first enqueued data")
        assertTrue(testData2.contentEquals(dequeuedData2), "Second dequeued data should match second enqueued data")
    }

    @Test
    fun testBoundedBehavior() = runTest {
        fail("not implemented")
    }
}
