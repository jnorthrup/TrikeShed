package borg.trikeshed.parse

import borg.trikeshed.lib.fib

class FibonacciSampler {
    private var triggerIndex = 0
    private var countdown = 1
    private var sampleAt = 0

    init {
        updateSamplePoint()
    }

    private fun updateSamplePoint() {
        sampleAt += fib(triggerIndex)
        triggerIndex++
    }

    fun shouldSample(recordIndex: Int): Boolean {
        if (recordIndex == sampleAt) {
            updateSamplePoint()
            return true
        }
        return false
    }
}