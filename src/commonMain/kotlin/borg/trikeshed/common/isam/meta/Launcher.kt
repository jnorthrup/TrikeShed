package borg.trikeshed.common.isam.meta

import borg.trikeshed.common.isam.IsamDataFile
import borg.trikeshed.common.isam.IsamMetaFileReader
import borg.trikeshed.humanReadableByteCountSI
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
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


        val r = Random(0)
        for (i in 0..10) {
            val row = r.nextInt(isam.size)
            val record = isam[row]

            //enumerate the fields in the record
            for ((index, c) in meta.constraints.withIndex()) {
                val field = record[ index ]
                println("field ${c.name} = ${field.a}")
            }
        }
    }
}

