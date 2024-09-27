package borg.trikeshed.parse.json

import borg.trikeshed.common.Files
import kotlin.test.Test

class TestJsonBitmap1 {
    @Test
    fun testAbitOfEverythingJson() {
        val lines =
            Files.readAllLines("src/commonTest/resources/big.json").map { it.encodeToByteArray().toUByteArray() }

        for (line in lines) {
            val encode = JsonBitmap.encode(line)
        }
    }
}