package borg.trikeshed.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import java.io.BufferedReader
import java.io.InputStreamReader

class OroborosDaemonBackoffTest {
    @Test
    fun testExponentialBackoffOnPollError() {
        val classPath = System.getProperty("java.class.path")
        val pb = ProcessBuilder(
            "java",
            "-cp", classPath,
            "-Dhttps.proxyHost=127.0.0.1",
            "-Dhttps.proxyPort=9999", // bogus port to force ConnectException -> IOException
            "borg.trikeshed.daemon.OroborosDaemon",
            "--watch",
            "--interval-ms", "100"
        )

        // JULES_API_KEY is required to prevent daemon from aborting early.
        pb.environment()["JULES_API_KEY"] = "fake-key"

        // Redirect stderr into stdout so we only read one stream
        pb.redirectErrorStream(true)

        val process = pb.start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))

        val delays = mutableListOf<Long>()
        var foundCycles = 0

        try {
            while (foundCycles < 5) {
                val line = reader.readLine()
                if (line == null) {
                    println("Process exited prematurely!")
                    break
                }
                println("GOT: $line") // to debug output
                if (line.contains("[OROBOROS] backoff=")) {
                    // Example format: [OROBOROS] backoff=100ms consecutiveErrors=1
                    val match = Regex("backoff=(\\d+)ms").find(line)
                    if (match != null) {
                        delays.add(match.groupValues[1].toLong())
                        foundCycles++
                    }
                }
            }
        } finally {
            process.destroy()
        }

        // Assert: first delay = 100ms, second = 200ms, third = 400ms, fourth = 500ms, fifth = 500ms.
        assertEquals(listOf(100L, 200L, 400L, 500L, 500L), delays)
    }
}
