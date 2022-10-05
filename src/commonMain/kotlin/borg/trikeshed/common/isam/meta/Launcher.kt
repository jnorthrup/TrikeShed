package borg.trikeshed.common.isam.meta

import borg.trikeshed.common.isam.IsamDataFile
import borg.trikeshed.common.isam.IsamMetaFileReader
import borg.trikeshed.common.isam.RecordMeta
import borg.trikeshed.humanReadableByteCountSI
import borg.trikeshed.lib.*
import platform.linux.getaliasent
import kotlin.random.Random


class Launcher {
    /**
     * open, read isam volume  $HOME/mpdata/import/klines/1m/BNB/USDT/final-BNB-USDT-1m.isam with meta found in  /home/jim/mpdata/import/klines/1m/BNB/USDT/final-BNB-USDT-1m.isam.meta
     *
     * print some sample records
     *
     */

    fun main(args: Array<String>) {
        val metafile = "/home/jim/mpdata/import/klines/1m/BNB/USDT/final-BNB-USDT-1m.isam.meta"
        val datafile = "/home/jim/mpdata/import/klines/1m/BNB/USDT/final-BNB-USDT-1m.isam"

        val meta = IsamMetaFileReader(metafile)
        println("open, read isam volume  $datafile with meta found in  $metafile")

        println("print some sample records")
        println("recordlen = ${meta.recordlen}")
        for (c in meta.constraints) {
            println(c)
        }

        //open the isamdatafile with this meta
        val isam = IsamDataFile(datafile, metafile)
        println("isam has ${isam.size.toLong().humanReadableByteCountSI} records")

        //the data file size as huamn readable string
        println("isam has ${isam.fileSize.humanReadableByteCountSI} bytes")

        val x: Cursor = isam
        //print some random records
        for (i in 1..10) {
            val r = Random.nextInt(0, x.size)
            //print the record index
            print("record $r: ")
            //get the cursor row
            val row = x row r

            //print some random fields from the row
            for (j in 1..10) {
                val f = Random.nextInt(0, row.size)
                //print colname/type and value
                row[f].let {    (value,b    )->
                    val (name,type,) = b()
                    print("col $j $name/$type=$value ")
                }
            }
            println()
        }
    }
}
