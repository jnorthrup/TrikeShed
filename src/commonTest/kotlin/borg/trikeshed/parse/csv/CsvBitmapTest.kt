package borg.trikeshed.parse.csv

import borg.trikeshed.common.Files
import kotlin.test.Test

class CsvBitmapTest {
    @OptIn(ExperimentalUnsignedTypes::class)
    @Test
    fun testAbitOfEverythingCsv() {
        val lines =
            Files.readAllLines("src/commonTest/resources/hi.csv").map { it.encodeToByteArray().toUByteArray() }

        for (line in lines) {
            val encode = CsvBitmap.encode(line)
        }
    }
}
