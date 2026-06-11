package borg.trikeshed.quic

import java.io.ByteArrayOutputStream

class QuicheRunner {
    suspend fun runClient(url: String) {
        val pb = ProcessBuilder("quiche/target/debug/quiche-client", "--no-verify", url)
        val proc = pb.start()

        val stdoutOut = ByteArrayOutputStream()
        proc.inputStream.use { it.copyTo(stdoutOut) }

        val stderrOut = ByteArrayOutputStream()
        proc.errorStream.use { it.copyTo(stderrOut) }

        val exitCode = proc.waitFor()

        if (exitCode != 0) {
            throw RuntimeException("Client failed with exit code $exitCode: ${stderrOut.toByteArray().decodeToString()}")
        }
        println(stdoutOut.toByteArray().decodeToString())
    }
}
