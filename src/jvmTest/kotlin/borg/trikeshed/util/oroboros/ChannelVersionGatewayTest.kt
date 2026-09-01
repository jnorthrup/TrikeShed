package borg.trikeshed.util.oroboros

import borg.trikeshed.lib.j
import borg.trikeshed.pijul.FileChanges
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The analog answers the gateway's three questions without a process.
 *
 * PijulVersionGateway shells to a `pijul` binary, so `isAvailable()` is a
 * question about the operator's machine and the patch algebra is a dependency
 * that may be absent or the wrong version. The model is already in this process;
 * there is nothing to shell to.
 */
class ChannelVersionGatewayTest {

    @Test
    fun theAlgebraCannotBeMissing() = runTest {
        // No probe, no exit code, no PATH lookup — it is linked in.
        assertTrue(ChannelVersionGateway().isAvailable())
    }

    @Test
    fun initIsAChannelAndRecordReturnsAContentAddressedRevision() = runTest {
        val gw = ChannelVersionGateway()
        assertTrue(gw.init("/home/a"))

        gw.stage("/home/a", FileChanges("README.md", inserts = listOf(0 j "hello\n")))
        val rev = gw.record("/home/a", "jim", "first")

        assertTrue(rev != null && rev.isNotEmpty(), "record returns a revision")
        // A patch id, not a commit sha: derived from content, so it is the same
        // on any machine that made the same change.
        assertEquals(64, rev!!.length, "blake/sha hex revision")
    }

    @Test
    fun recordingTheSameMessageTwiceYieldsTheSameRevision() = runTest {
        val a = ChannelVersionGateway().apply { init("/h") }
        a.stage("/h", FileChanges("f.kt", inserts = listOf(0 j "x\n")))
        val r1 = a.record("/h", "jim", "same message")

        val b = ChannelVersionGateway().apply { init("/h") }
        b.stage("/h", FileChanges("f.kt", inserts = listOf(0 j "x\n")))
        val r2 = b.record("/h", "someone-else", "same message")

        // Two peers, no communication, same revision — because the revision is
        // the content, not the ceremony around it.
        assertEquals(r1, r2)
    }

    @Test
    fun nothingStagedIsNothingRecorded() = runTest {
        val gw = ChannelVersionGateway()
        gw.init("/h")
        assertNull(gw.record("/h", "jim", "empty"), "an empty record is not a revision")
    }
}
