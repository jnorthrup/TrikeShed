package borg.trikeshed.serialization

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable

@Serializable
data class TestData(val name: String, val value: Int)

class CborKotlinxSerializerTest {
    @Test
    fun testNotImplemented() {
        kotlin.test.fail("not implemented")
    }
}
