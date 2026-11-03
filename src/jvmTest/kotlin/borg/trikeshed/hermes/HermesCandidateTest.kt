package borg.trikeshed.hermes

import borg.trikeshed.lib.*
import borg.trikeshed.graal.subvm.PythonFileLeases
import borg.trikeshed.userspace.nio.file.spi.FileLease
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.*

class HermesCandidateTest {
    @Test fun failedFileLeaseCloseRemainsOwnedForRetry() {
        val leases = PythonFileLeases(InMemoryFileOperations(), "/leases", { _, _ -> })
        var attempts = 0
        leases.leases["1"] = object : FileLease {
            override fun close() {
                if (++attempts == 1) error("lease close failed")
            }
        }

        assertFailsWith<IllegalStateException> { leases.close() }
        assertTrue("1" in leases.leases)
        assertTrue(PythonFileLeases.owners.containsKey(leases.owner))
        leases.close()
        assertTrue(leases.leases.isEmpty())
        assertFalse(PythonFileLeases.owners.containsKey(leases.owner))
        assertEquals(2, attempts)
    }

    @Test fun failedGuestCloseRetainsItsDependenciesUntilRetry() {
        val events = mutableListOf<String>()
        var guestAttempts = 0
        var socketAttempts = 0
        val owner = HermesCandidates()
        val candidate = HermesCandidate(
            guest = {
                events.add("guest")
                if (++guestAttempts == 1) error("guest close failed")
            },
            sockets = {
                events.add("sockets")
                if (++socketAttempts == 1) error("socket close failed")
            },
            services = { events.add("services") },
        )
        val importFailure = IllegalStateException("import failed")

        owner.retire(candidate, importFailure)
        assertEquals("guest close failed", importFailure.suppressed.single().message)
        assertEquals(_l["guest"], events)

        assertFailsWith<IllegalStateException> { owner.close() }
        assertEquals(_l["guest", "guest", "sockets"], events)

        owner.close()
        owner.close()
        assertEquals(_l["guest", "guest", "sockets", "sockets", "services"], events)
    }
}
