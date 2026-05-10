package borg.trikeshed.parse.confix

import borg.trikeshed.lib.getOrNull
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.view
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals

class Isam3FileReaderTest {
    @Test
    fun parsesPackedIoMetaColumns() {
        val yaml = """isam: 3
klines:
  klines.time:
    IoInstant: [Open_time, Close_time]
  klines.price:
    IoDouble: [Open, High, Low, Close]
views:
  klines: [Open_time, Close_time, Open, High, Low, Close]
""".trimIndent()

        val reader = Isam3FileReader.parse(yaml)

        assertEquals(3, reader.version)
        assertEquals(listOf("Open_time", "Close_time", "Open", "High", "Low", "Close"), reader.logicalNames("klines").toList())
        assertEquals("klines", reader.viewNames.getOrNull(0))
        assertEquals(6, reader.recordMeta("klines").size)
        assertEquals("Open_time", reader.recordMeta("klines").view.first().name)
    }
}
