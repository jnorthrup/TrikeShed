package borg.trikeshed.daemon

import borg.trikeshed.agent.keymux.*
import borg.trikeshed.agent.modelmux.*
import borg.trikeshed.forge.server.PiWire
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*

class PiAccessConfigTest {
    @Test fun daemonEnvironmentAuthenticatesThePiCatalog() = runBlocking {
        val token = "pi-config-test-0123456789abcdef"
        val hash = OroborosDaemon.piAccessHash(null) { if (it == "PI_ACCESS_TOKEN") token else null }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mux = ModelMux(KeyMux { }) { }
        val wire = PiWire(owner, EmptyCoroutineContext, BreadcrumbElement(), { mux }, hash)
        try {
            fun request(value: String) = "GET /api/pi/models HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer $value\r\n\r\n"
            assertEquals(200, wire.route("GET", "/api/pi/models", request(token), null)?.status)
            assertEquals(401, wire.route("GET", "/api/pi/models", request("different-token-0123456789"), null)?.status)
        } finally {
            wire.drain()
            owner.cancel()
        }
    }

    @Test fun hashConfigurationAndExistingPropertyAreAccepted() {
        val hash = ContentId.of("pi-config-test-0123456789abcdef".encodeToByteArray()).hex
        assertEquals(hash, OroborosDaemon.piAccessHash(null) { if (it == "PI_ACCESS_TOKEN_SHA256") hash else null })
        assertEquals(hash, OroborosDaemon.piAccessHash(hash) { error("Explicit property is authoritative") })
        assertNull(OroborosDaemon.piAccessHash(null) { null })
    }

    @Test fun invalidConfigurationFailsBeforePiStarts() {
        assertFailsWith<IllegalArgumentException> { OroborosDaemon.piAccessHash(null) { "conflicting-config" } }
        for (token in s_["short", "pi-config-test-0123456789\nheader"].view)
            assertFailsWith<IllegalArgumentException> {
                OroborosDaemon.piAccessHash(null) { if (it == "PI_ACCESS_TOKEN") token else null }
            }
        assertFailsWith<IllegalArgumentException> {
            OroborosDaemon.piAccessHash(null) { if (it == "PI_ACCESS_TOKEN_SHA256") "invalid" else null }
        }
    }
}
