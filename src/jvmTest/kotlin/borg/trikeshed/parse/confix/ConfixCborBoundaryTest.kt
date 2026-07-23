package borg.trikeshed.parse.confix

import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

class ConfixCborBoundaryTest {
    @Test
    fun testByteArrayBuilderIsInternal() {
        val rootDir = File(System.getProperty("user.dir"))
        val encoderFile = File(rootDir, "src/commonMain/kotlin/borg/trikeshed/parse/confix/ConfixCborEncoder.kt")
        assertTrue(encoderFile.exists(), "Encoder file must exist")

        val content = encoderFile.readText()
        assertTrue(content.contains("internal class ByteArrayBuilder") || content.contains("private class ByteArrayBuilder"), "ByteArrayBuilder must be internal or private to avoid leaking implementation details")
    }
}
