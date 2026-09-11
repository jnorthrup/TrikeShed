package borg.trikeshed.ipns

import borg.trikeshed.lib.j
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds

class IpnsJournalTest {
    private val crypto = JvmIpnsCrypto()
    private val now = Instant.parse("2026-09-11T00:00:00Z")
    private val value = "/ipfs/${IpnsCid.raw(byteArrayOf(1))}"

    @Test fun durableIdentityVersionsPermissionsLeaseAndTornTail() {
        val directory = Files.createTempDirectory("ipns-journal-")
        val path = directory.resolve("identity")
        var journal = IpnsJournal.open(path.toString(), crypto)
        val identity = journal.identity
        try {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
            assertFails { IpnsJournal.open(path.toString(), crypto) }
            val record = IpnsRecord.create(identity, value, 7u, now + 60.seconds, 10_000_000_000u, crypto)
            journal.append(record)
            journal.close()
            Files.write(path, byteArrayOf(0, 0, 1), APPEND) // Crash fixture, outside the production file path.
            journal = IpnsJournal.open(path.toString(), crypto)
            assertEquals(identity.name, journal.identity.name)
            assertContentEquals(identity.privateKey, journal.identity.privateKey)
            assertEquals(7uL, journal.latest!!.sequence)
            assertContentEquals(record.bytes, journal.latest!!.bytes)
            assertEquals((104 + 4 + record.bytes.size + 32).toLong(), Files.size(path))
        } finally { journal.close(); Files.delete(path); Files.delete(directory) }
    }

    @Test fun cancelledOwnerFinallyReleasesJournalLease() = runTest {
        val directory = Files.createTempDirectory("ipns-cancel-")
        val path = directory.resolve("identity")
        val journal = IpnsJournal.open(path.toString(), crypto)
        val peer = IpnsDhtTest().peer(1)
        val dialer = IpnsDhtTest.CodecDialer { _, request -> request }
        val dht = IpnsDht(dialer, crypto, 1 j { peer }, IpnsDhtLimits(replication = 1,
            minValidResponses = 1, allowPrivateAddresses = true))
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            val publisher = IpnsPublisher(journal, dht, crypto, coroutineContext)
            publisher.open()
            try { awaitCancellation() } finally { publisher.close() }
        }
        owner.cancelAndJoin()
        val reopened = IpnsJournal.open(path.toString(), crypto)
        try { assertEquals(journal.identity.name, reopened.identity.name) }
        finally { reopened.close(); Files.delete(path); Files.delete(directory) }
    }

    @Test fun operationTimeoutRetainsWorkerAndRetriesDurableRecord() = runTest {
        val directory = Files.createTempDirectory("ipns-retry-")
        val path = directory.resolve("identity")
        val journal = IpnsJournal.open(path.toString(), crypto)
        val peer = IpnsDhtTest().peer(1)
        var first = true
        val dialer = IpnsDhtTest.CodecDialer { _, request ->
            if (first) { first = false; delay(2000) }
            if (request.type == IpnsDhtType.PUT_VALUE) request else IpnsDhtMessage(request.type)
        }
        val dht = IpnsDht(dialer, crypto, 1 j { peer }, IpnsDhtLimits(replication = 1,
            minValidResponses = 1, allowPrivateAddresses = true, rpcTimeoutMillis = 1000, operationTimeoutMillis = 1000))
        val publisher = IpnsPublisher(journal, dht, crypto, coroutineContext,
            IpnsPublicationPolicy(10.seconds, 1.seconds, 4.seconds, 1.seconds), now = { now })
        try {
            publisher.open()
            assertFailsWith<TimeoutCancellationException> { publisher.publish(value) }
            val durable = publisher.latest!!.bytes
            advanceTimeBy(1001); runCurrent()
            assertTrue(publisher.lastReport?.complete == true)
            assertContentEquals(durable, publisher.lastReport!!.record.bytes)
            assertEquals(0uL, publisher.lastReport!!.record.sequence)
        } finally { publisher.close(); Files.delete(path); Files.delete(directory) }
    }

    @Test fun serialPublicationRenewalAndDrainPersistBeforeNetwork() = runTest {
        val directory = Files.createTempDirectory("ipns-publisher-")
        val path = directory.resolve("identity")
        val journal = IpnsJournal.open(path.toString(), crypto)
        val peer = IpnsDhtTest().peer(1)
        var puts = 0
        val dialer = IpnsDhtTest.CodecDialer { _, request ->
            if (request.type == IpnsDhtType.PUT_VALUE) {
                puts++
                assertContentEquals(journal.latest!!.bytes, request.record!!.value)
                request
            } else IpnsDhtMessage(request.type)
        }
        var clock = now
        val dht = IpnsDht(dialer, crypto, 1 j { peer }, IpnsDhtLimits(replication = 1,
            minValidResponses = 1, allowPrivateAddresses = true))
        val publisher = IpnsPublisher(journal, dht, crypto, coroutineContext,
            IpnsPublicationPolicy(10.seconds, 1.seconds, 4.seconds, 1.seconds), now = { clock })
        try {
            publisher.open()
            assertTrue(publisher.publish(value).complete)
            val first = publisher.latest!!
            assertEquals(0uL, first.sequence)
            clock += 7.seconds
            advanceTimeBy(4001); runCurrent()
            assertTrue(puts >= 2)
            assertEquals(0uL, publisher.latest!!.sequence)
            assertTrue(publisher.latest!!.validUntil > first.validUntil)
            val secondValue = "/ipfs/${IpnsCid.raw(byteArrayOf(2))}"
            val pending = async { publisher.publish(secondValue) }
            runCurrent()
            publisher.drain()
            assertTrue(pending.await().complete)
            assertEquals(1uL, publisher.latest!!.sequence)
            assertFails { publisher.publish(value) }
            assertEquals(0, dialer.active)
            val reopened = IpnsJournal.open(path.toString(), crypto)
            try { assertEquals(1uL, reopened.latest!!.sequence); assertEquals(secondValue, reopened.latest!!.value) }
            finally { reopened.close() }
        } finally { publisher.close(); Files.deleteIfExists(path); Files.deleteIfExists(directory.resolve("identity.lock")); Files.delete(directory) }
    }
}
