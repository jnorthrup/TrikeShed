import java.io.File
val file = File("src/commonMain/kotlin/borg/trikeshed/couch/ViewServer.kt")
val content = file.readText()
file.writeText(content + "\n// WorkDrained")
