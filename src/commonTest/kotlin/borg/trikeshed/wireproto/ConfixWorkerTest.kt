/*
 * Copyright (c) 2024 TrikeShed Authors
 * This file is part of TrikeShed, released under the AGPLv3 license.
 */

package borg.trikeshed.wireproto

import borg.trikeshed.context.nuid.*
import borg.trikeshed.lib.j
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfixWorkerTest {

    @Test
    fun testSerializationDeserialization() = runTest {
        val worker = ConfixWorker()

        val cap = Capability.Process("spawn")
        // Use an actual string-derived nonce since byte array decodeToString loses data on arbitrary bytes
        val nonce = Nonce.Derived("my-derived-nonce-seed")
        val subnet = Subnet.parse("global.mesh")
        val testNuid = nuid(cap, nonce, subnet)

        val verb = "execute"
        val payload = "hello world".encodeToByteArray()

        val action = testNuid j (verb j payload)

        val result = worker.invoke(action)

        // Capability
        val expectedCap = testNuid.a
        val actualCap = result.a.a
        assertEquals(expectedCap.category, actualCap.category)

        // Nonce
        val expectedNonce = testNuid.b.a
        val actualNonce = result.a.b.a
        assertTrue(expectedNonce.bytes.contentEquals(actualNonce.bytes), "Nonce bytes mismatch")

        // Subnet
        assertEquals(testNuid.b.b.toString(), result.a.b.b.toString())

        // Verb
        assertEquals(verb, result.b.a)

        // Payload
        assertTrue(payload.contentEquals(result.b.b), "Payload mismatch")
    }

}
