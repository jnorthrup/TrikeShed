package borg.trikeshed.isam.meta

import borg.trikeshed.common.homedir
import borg.trikeshed.isam.IsamMetaFileReader
import borg.trikeshed.humanReadableByteCountSI
import borg.trikeshed.isam.IsamDataFile
import borg.trikeshed.lib.*
import kotlin.random.Random

class Launcher {
    /**
     * open, read isam volume  $HOME/mpdata/import/klines/1m/BNB/USDT/final-BNB-USDT-1m.isam with meta found in  /home/jim/mpdata/import/klines/1m/BNB/USDT/final-BNB-USDT-1m.isam.meta
     *
     * print some sample records
     *
     */

    fun main(args: Array<String>) {
        //print DEBUG using homedir
        println("DEBUG homedir = $homedir")
        val metafile = homedir+"/mpdata/import/klines/1m/BNB/USDT/final-BNB-USDT-1m.isam.meta"
        val datafile = homedir+"/mpdata/import/klines/1m/BNB/USDT/final-BNB-USDT-1m.isam"

        //show filenames
        println("DEBUG metafile = $metafile")
        println("DEBUG datafile = $datafile")


        val meta = IsamMetaFileReader(metafile).also { it.open() }
        println("open, read isam volume  $datafile with meta found in  $metafile")

        println("print some sample records")
        println("recordlen = ${meta.recordlen}")
        for (c in meta.constraints) {
            println(c)
        }

        // open the isamdatafile with this meta
        val isam = IsamDataFile(datafile, metafile)
        isam.open()// not automatic because of native/actual split

        println("isam has ${isam.size.toLong().humanReadableByteCountSI} records")

        // the data file size as huamn readable string
        println("isam has ${isam.fileSize.humanReadableByteCountSI} bytes")

        val x: Cursor = isam
        // print some random records
        for (i in 1..10) {
            val r  = (0 until x.size).random()
            println("DEBUG: row $r = ${x[r]}")


            // get the cursor row
            val row = (x row r) .toList()

            // print some random fields from the row
            for (j in 1..10) {
                val f = (0 until row.size).random()
                // print colname/type and value
                row[f].let { (value, b) ->
                    val (name, type,) = b()
                    print("col $j $name/$type=$value ")
                }
            }
            println()
        }
    }
}

