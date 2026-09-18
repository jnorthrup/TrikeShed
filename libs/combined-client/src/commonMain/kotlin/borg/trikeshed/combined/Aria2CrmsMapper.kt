package borg.trikeshed.combined

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * Maps the Aria2/RPC client interactions to the Confix API interface and blackboard
 * presence cursor tree by leveraging CRMS facets.
 *
 * TODO: Assesses libs/ and htx available and mitigate the gap of time since retirement.
 * Make a full standalone best-attempt for all commonMain targets without platform
 * io specializations that are not SPI-wide features.
 */
class Aria2CrmsMapper(
    val combinedClient: CombinedClientElement
) {
    /**
     * Executes the RPC command and wraps the string result as a Confix Cursor
     * with the appropriate CRMS facet.
     */
    suspend fun executeAsCursor(command: String, args: List<String>): Cursor {
        val result = combinedClient.executeRpc(command, args)

        // Wrap the single string result into a Confix row vector.
        val rowVec = createRowVecForCrms(result)

        // Return a cursor containing exactly one row
        return 1 j { _: Int -> rowVec }
    }

    private fun createRowVecForCrms(result: String): RowVec {
        val meta = ColumnMeta("rpcResult", IOMemento.IoString)
        val cell = result j meta
        return 1 j { _: Int -> cell }
    }
}
