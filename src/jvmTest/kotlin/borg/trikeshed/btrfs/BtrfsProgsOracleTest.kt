package borg.trikeshed.btrfs

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BtrfsProgsOracleTest {

    @Test
    fun missing_btrfs_progs_is_reported_as_oracle_blocker() = runTest {
        val path = Files.createTempFile("trikeshed-btrfs-oracle-", ".img")
        try {
            val receipt = BtrfsProgsOracle.checkReadOnly(path.toString(), btrfsPath = null)
            assertEquals(BtrfsProgsOracleSource.ABSENT, receipt.source)
            assertNull(receipt.btrfsPath)
            assertNull(receipt.exitCode)
            assertFalse(receipt.checkPassed)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
