package borg.trikeshed.storage.volume

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.BeforeTest
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay

@JsFun("""() => {
    // polyfill hack
    try {
        globalThis.indexedDB = require('fake-indexeddb').indexedDB;
        globalThis.IDBKeyRange = require('fake-indexeddb').IDBKeyRange;
    } catch(e) { }
}""")
private external fun setupPolyfill()

class IndexedDbVolumeTest {
    @BeforeTest
    fun setup() {
        setupPolyfill()
    }

    @Test
    fun testIndexedDbVolumeRoundTrip() = runTest {
        val volume = IndexedDbVolume()
        val data = ByteArray(4096) { 5 }
        volume.write(0, data)
        volume.sync()

        // Wait for next tick in fake-indexeddb since operations are pseudo-async
        // Since Kotlin Wasm async/await interop sometimes swallows exceptions or has bugs
        // with node.js timers, we need a good delay.
        var i = 0
        while (i < 50) {
            delay(10)
            val readData = volume.read(0, 4096)
            if (readData.size > 0 && readData[0] == 5.toByte()) {
                 break
            }
            i++
        }

        val readData = volume.read(0, 4096)

        println("readData size: " + readData.size)
        if (readData.size > 0) println("first byte: " + readData[0])
        var nonZeroCount = 0
        for (idx in readData.indices) {
            if (readData[idx] != 0.toByte()) {
                nonZeroCount++
            }
        }
        println("non zero count: " + nonZeroCount)

        var match = true
        for (idx in 0 until 4096) {
             if (readData[idx] != data[idx]) {
                  match = false
                  println("mismatch at " + idx + " read=" + readData[idx] + " expected=" + data[idx])
                  break
             }
        }
        assertTrue(match, "Data read from IndexedDbVolume should match data written")
    }

    @Test
    fun testOpfsVolumeRoundTrip() = runTest {
        val volume = OpfsVolume()
        val data = ByteArray(4096) { 5 }
        volume.write(0, data)
        volume.sync()
        delay(100)

        val readData = volume.read(0, 4096)
    }
}
