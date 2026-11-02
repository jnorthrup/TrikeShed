package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.UringOp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UnixNameTest {
    /** '@' leads an abstract name: the same bytes, spelled with a 0 where the text has the '@'. */
    @Test fun anAtSignLeadsAnAbstractNameAndAnyOtherTextIsAPath() {
        assertContentEquals("\u0000sctp-a".encodeToByteArray(), unixName("@sctp-a"))
        assertContentEquals("/tmp/sctp-a".encodeToByteArray(), unixName("/tmp/sctp-a"))
        assertEquals(0, unixName("").size)
    }

    /** A name lives in the 26 bytes after the family, and never ends in the zeros that pad the slot. */
    @Test fun aNameFitsTheAddressSlotAndDoesNotEndInZero() {
        assertEquals(UringOp.DATAGRAM_ADDRESS - 2, UNIX_NAME_MAX)
        assertEquals(UNIX_NAME_MAX, unixName("@" + "n".repeat(UNIX_NAME_MAX - 1)).size)
        for (text in s_["@" + "n".repeat(UNIX_NAME_MAX), "/" + "n".repeat(UNIX_NAME_MAX), "@", "a\u0000"].view)
            assertFailsWith<IllegalArgumentException>(text) { unixName(text) }
    }

    /** BIND takes the family and the name to its exact length: the length is part of an abstract name. */
    @Test fun theBindAddressIsTheFamilyThenTheNameToItsExactLength() {
        val name = unixName("@sctp-a")
        val address = sockaddrUnixName(name)
        assertEquals(2 + name.size, address.size)
        assertEquals(SocketDomain.AF_UNIX.posix, sockaddrFamily(address))
        assertContentEquals(name, address.copyOfRange(2, address.size))
        // No name: Linux's autobind is a bind of the family alone.
        assertEquals(2, sockaddrUnixName(unixName("")).size)
    }

    /** A datagram window ends in a 28-byte slot: the family, the name, zeros. */
    @Test fun theSlotIsTheFamilyTheNameAndZerosToTheSlotsEnd() {
        val name = unixName("/tmp/sctp-a")
        val slot = unixSlot(name)
        assertEquals(UringOp.DATAGRAM_ADDRESS, slot.size)
        assertEquals(SocketDomain.AF_UNIX.posix, sockaddrFamily(slot))
        assertContentEquals(name, slot.copyOfRange(2, 2 + name.size))
        assertContentEquals(ByteArray(slot.size - 2 - name.size), slot.copyOfRange(2 + name.size, slot.size))
    }

    @Test fun unixHostReadsTheNameAfterTheScheme() {
        assertContentEquals(unixName("@sctp-a"), parseWhole("unix:@sctp-a") { unixHost() })
        assertContentEquals(unixName("/tmp/sctp-a"), parseWhole(" unix:/tmp/sctp-a ") { unixHost() })
        for (text in s_["127.0.0.1:3200", "unix:", "unix:@", "unix:" + "x".repeat(UNIX_NAME_MAX + 1)].view)
            assertNull(parseWhole(text) { unixHost() }, text)
    }
}
