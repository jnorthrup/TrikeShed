package borg.trikeshed.sctp

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

class PartialReliabilityBuffer(val capacity: Int) {
    private var elements: Array<Pair<Int, ByteArray>?> = arrayOfNulls(capacity)
    private var count = 0
    private var head = 0

    fun enqueue(tsn: Int, data: ByteArray) {
        if (count == capacity) {
            // Drop oldest, which is at head
            elements[head] = tsn to data
            head = (head + 1) % capacity
        } else {
            val tail = (head + count) % capacity
            elements[tail] = tsn to data
            count++
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun getAllUnacked(): Series<Pair<Int, ByteArray>> = count j { i ->
        elements[(head + i) % capacity] as Pair<Int, ByteArray>
    }
}
