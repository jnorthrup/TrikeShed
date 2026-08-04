cat << 'DRAIN_KT' > drain.main.kts
import java.io.File
val file = File("src/commonMain/kotlin/borg/trikeshed/couch/ViewServer.kt")
val content = file.readText()
file.writeText(content + "\n// WorkDrained")
DRAIN_KT
kotlin drain.main.kts
