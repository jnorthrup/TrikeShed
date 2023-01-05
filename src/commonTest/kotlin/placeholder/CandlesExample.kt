package placeholder

import FileBuffer
import borg.trikeshed.common.CSVUtil
import borg.trikeshed.common.homedir
import borg.trikeshed.lib.Cursor
 import borg.trikeshed.lib.head
import openFileBuffer
import kotlin.test.Test

class CandlesExample {
      /**
     * read ~/mpdata/import/klines/1m/BTC/USDT/final-BTC-USDT-1m.csv
     * and write some mp-specific columns back to isam
     */ 
    @Test
    fun testMPCsv() {

        val homedir1 = homedir
        val fname = "${homedir1}/mpdata/import/klines/1m/BTC/USDT/final-BTC-USDT-1m.csv"
        val open: FileBuffer =  openFileBuffer(fname, readOnly = true)
        val csv:Cursor = CSVUtil.parseConformant(open)

        csv.head()
        open.close()
    }
}








