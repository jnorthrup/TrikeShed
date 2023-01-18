package placeholder


import borg.trikeshed.common.CSVUtil
import borg.trikeshed.common.FileBuffer
import borg.trikeshed.common.homedir
import borg.trikeshed.common.use
import borg.trikeshed.isam.ColMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.Cursor
import borg.trikeshed.lib.head
import borg.trikeshed.lib.map
import borg.trikeshed.lib.meta
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
        FileBuffer_open(fname, readOnly = true).use { fileBuffer ->
            val csv: Cursor = CSVUtil.parseConformant(fileBuffer)

            //show the IoMemento for the first row
            csv.meta.map { it: ColMeta -> it.name to it.type as IOMemento }.forEach { (name: String, type: IOMemento) ->
                println("$name: $type")
            }

            csv.head()
        }
    }
}
