package borg.trikeshed

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.fail

class ConflictMarkerAuditTest {
    @Test
    fun positioningPaperFilesDoNotContainConflictMarkers() {
        val filesToCheck = listOf(
            "src/commonMain/kotlin/borg/trikeshed/forge/shell/HtmlShell.kt",
            "src/commonMain/kotlin/borg/trikeshed/wireproto/ActionDecoder.kt"
        )
        for (filePath in filesToCheck) {
            val file = File(filePath)
            if (!file.exists()) {
                val absolutePath = File(".").absolutePath
                fail("File $filePath does not exist relative to $absolutePath")
            }
            val content = file.readText()
            assertFalse(content.contains("<<<<<<< SEARCH"), "File $filePath contains <<<<<<< SEARCH")
            assertFalse(content.contains("======="), "File $filePath contains =======")
            assertFalse(content.contains(">>>>>>> REPLACE"), "File $filePath contains >>>>>>> REPLACE")
            assertFalse(content.contains(">>>>>>>"), "File $filePath contains >>>>>>>")
        }
    }
}
