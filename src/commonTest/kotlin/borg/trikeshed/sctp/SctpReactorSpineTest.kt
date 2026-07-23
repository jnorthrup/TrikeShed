/*
 * Copyright (C) 2024 TrikeShed
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package borg.trikeshed.sctp

import borg.trikeshed.context.ElementState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import borg.trikeshed.reactor.SctpReactorEndpoint
import borg.trikeshed.reactor.MeshActionResult
import borg.trikeshed.reactor.PeerAddress
import borg.trikeshed.lcnc.reactor.ReactorAction
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid

class SctpReactorSpineTest {

    @Test
    fun testSctpSocketCreationAndBinding() = runTest {
        val element = openSctpElement()
        val assoc = element.bind(3000)
        assertEquals(SctpState.CLOSED, assoc.state)
        assertEquals(3000L, assoc.associationId)
        element.close()
    }

    @Test
    fun testConnectionEstablishmentAndBidirectionalMessaging() = runTest {
        val serverElement = openSctpElement()
        val clientElement = openSctpElement()

        // 1. Server binds
        val serverAssoc = serverElement.bind(8080)

        // 2. Client initiates connection
        val clientAssoc = clientElement.connect("127.0.0.1", 8080)
        assertEquals(SctpState.COOKIE_WAIT, clientAssoc.state)

        // Mocking the wire for now as we test the spine interactions:
        // Server sends INIT_ACK, client receives it
        val initAck = SctpInitAckChunk(0u, 0u, 10u, 10u, 0u)
        val stateAfterInitAck = clientElement.handleInitAck(clientAssoc.associationId, initAck, byteArrayOf(1,2,3))
        assertEquals(SctpState.COOKIE_ECHOED, stateAfterInitAck)

        // Client sends COOKIE_ECHO, server receives it
        val cookieEcho = SctpCookieEchoChunk(byteArrayOf(1,2,3))
        val serverState = serverElement.handleCookieEcho(serverAssoc.associationId, cookieEcho)
        assertEquals(SctpState.ESTABLISHED, serverState)

        // Server sends COOKIE_ACK, client receives it
        val clientFinalState = clientElement.handleCookieAck(clientAssoc.associationId)
        assertEquals(SctpState.ESTABLISHED, clientFinalState)

        serverElement.close()
        clientElement.close()
    }

    @Test
    fun testConnectionTeardown() = runTest {
        val element = openSctpElement()
        element.close()
        assertEquals(ElementState.CLOSED, element.lifecycleState)
    }
}
