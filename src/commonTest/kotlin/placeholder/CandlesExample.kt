package placeholder


import borg.trikeshed.common.CSVUtil
import borg.trikeshed.common.FileBuffer
import borg.trikeshed.common.homedir
import borg.trikeshed.lib.Cursor
 import borg.trikeshed.lib.head
import kotlin.test.Test
import borg.trikeshed.common.open as FileBuffer_open

class CandlesExample {
    /**
     * read ~/mpdata/import/klines/1m/BTC/USDT/final-BTC-USDT-1m.csv
     * and write some mp-specific columns back to isam
     */
    @Test
    fun testMPCsv() {

        val homedir1 = homedir
        val fname = "${homedir1}/mpdata/import/klines/1m/BTC/USDT/final-BTC-USDT-1m.csv"
        val open: FileBuffer = FileBuffer_open(fname, readOnly = true)
        val csv: Cursor = CSVUtil.parseConformant(open)

        csv.head()
        open.close()
    }
}