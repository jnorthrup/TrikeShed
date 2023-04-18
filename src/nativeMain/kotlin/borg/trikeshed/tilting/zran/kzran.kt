@file:OptIn(ExperimentalUnsignedTypes::class)

package borg.trikeshed.tilting.zran

import borg.trikeshed.isam.meta.PlatformCodec.Companion.currentPlatformCodec.readULong
import borg.trikeshed.isam.meta.PlatformCodec.Companion.currentPlatformCodec.readUShort
import borg.trikeshed.isam.meta.PlatformCodec.Companion.currentPlatformCodec.writeULong
import borg.trikeshed.isam.meta.PlatformCodec.Companion.currentPlatformCodec.writeUShort
import borg.trikeshed.lib.CZero.nz
import borg.trikeshed.lib.CZero.z
import borg.trikeshed.lib.`↺`
import borg.trikeshed.native.HasPosixErr.Companion.posixFailOn
import borg.trikeshed.native.HasPosixErr.Companion.posixRequires
import kotlinx.cinterop.*
import platform.posix.*
import platform.zlib.*


const val RAW = -15
const val ZLIB = 15
const val GZIP = 31
const val WINSIZE = 32768U
const val CHUNK = 16384


class Point(
    var output: ULong,
    var input: ULong,
    win: UByteArray,
    val winsize: UShort = win.size.toUShort(),
    windowSupplier: () -> UByteArray = win.`↺`,
) {
    val window: UByteArray by lazy(windowSupplier)
}

class GzIndex {
    val have: Int get() = list.size
    val mode: Int = 0
    var length: ULong = 0u
    var list: MutableList<Point> = mutableListOf()
    var fpName: String? = null
    val indexFp: CPointer<FILE> by lazy {
        val name = fpName ?: throw IllegalStateException("Index file name not set")
        val fp = fopen(name, "rb")
        posixRequires(fp != null) { "Error: could not open index file $name" }
        fp!!
    }


    fun getWindow(index: Int): UByteArray {
        if (index < 0 || index >= list.size) {
            throw IndexOutOfBoundsException("Index out of bounds: $index")
        }
        return list[index].window
    }


    fun build(gzFile: FILE, span: ULong): Int {
        val strm = z_stream_s(NativePtr.NULL)
        val buf = UByteArray(CHUNK)
        val win = UByteArray(WINSIZE.toInt())
        var totin = 0
        var totout = 0
        var mode = 0
        var ret: Int = 0
        var last: ULong = 0U
        val stride0 = CHUNK
        var stride = stride0
        do {
            val buf = buf.pin()
            if (strm.avail_in == 0u) {
                val pinnedBuf = buf.addressOf(0)
                strm.avail_in = fread(pinnedBuf, 1u, stride.toULong(), gzFile.ptr).toUInt()
                totin += strm.avail_in.toInt()
                strm.next_in = pinnedBuf
                if (strm.avail_in < stride.toUInt() && ferror(gzFile.ptr).nz) {
                    ret = Z_ERRNO
                    break
                }

                if (mode == 0) {


                    mode =
                        if (strm.avail_in == 0u) RAW else if ((strm.next_in!![0] and 15u) == 8.toUByte()) ZLIB else if (strm.next_in!![0] == 0x1f.toUByte()) GZIP else RAW
                    ret = inflateInit2(strm.ptr, mode)
                    if (ret != Z_OK)
                        break
                }
            }

            if (strm.avail_out.z) {
                strm.avail_out = WINSIZE
                strm.next_out = win.pin().addressOf(0)

            }

            if (mode == RAW && list.isEmpty())


                strm.data_type = 0x80
            else {

                val before = strm.avail_out
                ret = inflate(strm.ptr, Z_BLOCK)
                totout += before.toInt() - strm.avail_out.toInt()
            }

            if ((strm.data_type and 0xc0) == 0x80 && (list.isEmpty() || totout.toULong() - last >= span)) {

                val bits = strm.data_type and 7

                if (bits.nz) {
                    stride = 1
                    continue
                } else stride = stride0


                val deflater = z_stream_s(NativePtr.NULL)
                var ret1 = deflateInit(deflater.ptr, Z_BEST_COMPRESSION)
                var compressedData = UByteArray(32 * 1024)
                val compressedDataBuffer = compressedData.pin()
                deflater.next_in = win.pin().addressOf(0)
                deflater.avail_in = WINSIZE
                deflater.next_out = compressedDataBuffer.addressOf(0)
                deflater.avail_out = compressedData.size.toUInt()
                ret1 = deflate(deflater.ptr, Z_FINISH)
                posixRequires(ret1 == Z_STREAM_END) { "Compressed data $ret1 isn't Z_STREAM_END (${Z_STREAM_END})" }
                deflateEnd(deflater.ptr)
                compressedDataBuffer.unpin()
                val compressedDataSize = compressedData.size - deflater.avail_out.toInt()
                posixRequires(compressedDataSize.toULong() < UShort.MAX_VALUE * 1UL) { "Compressed data size too large: $compressedDataSize" }

                compressedData = compressedData.sliceArray(0 until compressedDataSize)

                val point = Point((totin - strm.avail_in.toInt()).toULong(), totout.toULong(), compressedData)
                list += point
                last = totout.toULong()
            }
            if (ret == Z_STREAM_END && mode == GZIP && (strm.avail_in.nz || ungetc(
                    getc(gzFile.ptr),
                    gzFile.ptr
                ) != EOF)
            ) {
                ret = inflateReset2(strm.ptr, GZIP)
            }


        } while (ret == Z_OK)

        inflateEnd(strm.ptr)

        posixRequires(ret != Z_STREAM_END) { "Error: $ret != Z_STREAM_END (${Z_STREAM_END})" }
        length = totout.toULong()
        return have
    }


    fun writeIndex(indexFname: String): Int = withIndexFile(indexFname) { indexFp ->
        //@formatter:off short n sweet serializer
        "kzra".encodeToByteArray().usePinned { fwrite(it.addressOf(0), 1, "kzra".encodeToByteArray().size.toULong(), indexFp) }
        list.forEach { writeULong(it.output).usePinned { fwrite(it.addressOf(0), 1, ULong.SIZE_BYTES.toULong(), indexFp) } }
        writeULong(ULong.MAX_VALUE).usePinned { fwrite(it.addressOf(0), 1, ULong.SIZE_BYTES.toULong(), indexFp) }
        list.forEach { writeULong(it.input).usePinned { fwrite(it.addressOf(0), 1, ULong.SIZE_BYTES.toULong(), indexFp) } }
        list.forEach { writeUShort(it.winsize).usePinned { fwrite(it.addressOf(0), 1, UShort.SIZE_BYTES.toULong(), indexFp) } }
        list.forEach { it.window.apply { usePinned { fwrite(it.addressOf(0), 1, size.toULong(), indexFp) } } }
        0
    }   // @formatter:on


    @OptIn(ExperimentalUnsignedTypes::class)
    fun readIndex(indexFname: String): Int = withIndexFile(indexFname) { indexFp ->
        val magic = UByteArray(4)
        magic.usePinned { fread(it.addressOf(0), 1, 4u, indexFp) }
        val magicStr = magic.toByteArray().decodeToString()
        posixFailOn(magicStr != "kzra") { ("Error: invalid index file format: '$magicStr' is not 'kzra'") }
        val pointOutput = mutableListOf<ULong>()
        val pointInput = mutableListOf<ULong>()
        val windowSizes = mutableListOf<UShort>()

        val buf = ByteArray(ULong.SIZE_BYTES)
        val uLong = readULong(buf)
        buf.usePinned { tempOutput ->
            while (true) {
                fread(tempOutput.addressOf(0), 8, 1u, indexFp)
                if (uLong == ULong.MAX_VALUE) break
                pointOutput.add(uLong)
            }

            for (i in pointOutput.indices) {
                fread(tempOutput.addressOf(0), 8, 1u, indexFp)
                pointInput.add(readULong(buf))
            }

            for (i in pointOutput.indices) {
                fread(tempOutput.addressOf(0), 8, 1u, indexFp)
                val uShort = readUShort(buf)
                windowSizes.add(uShort)
            }
        }

        list.clear()


        //initial offset =4

        val windowOrigin = (4 + pointOutput.size * (ULong.SIZE_BYTES * 2 + UShort.SIZE_BYTES)).toULong()
        val windowOffsets =
            (listOf(0.toUShort(), windowOrigin.toUShort()) + windowSizes).zipWithNext().map { it.first + it.second }
                .toMutableList()


        //is this stdin?
        val isStdin = (indexFname == "-")

        for (i: Int in pointOutput.indices)
            list += Point(pointOutput[i], pointInput[i], UByteArray(0),
                windowSupplier = if (isStdin) {
                    val window = UByteArray(windowSizes[i].toInt())
                    window.usePinned { fread(it.addressOf(0), 1, windowSizes[i].toULong(), stdin) }
                    window.`↺`
                } else fun(): UByteArray {
                    return withIndexFile(indexFname) { indexFp ->
                        fseek(indexFp, windowOffsets[i].toLong(), SEEK_SET)
                        val window = UByteArray(windowSizes[i].toInt())
                        window.usePinned {
                            fread(it.addressOf(0), 1, windowSizes[i].toULong(), indexFp)
                        }
                        window
                    }
                }
            )
        0
    }

    private fun <T> withIndexFile(indexFname: String, block: (CPointer<FILE>) -> T): T {
        try {
            return if (indexFname == "-") {
                block(stdin!!)
            } else {
                val indexFp = fopen(indexFname, "rb")
                posixFailOn(indexFp == null) { ("Error: could not open index file $indexFname") }

                block(indexFp!!)
            }
        } finally {
            fclose(indexFp)
        }
    }
}


fun createindex(args: Array<String>) {
    /**
     *  [ gzFName  ] [idxfname.index]
     *
     *  gzFName: input file name or stdin
     *  idxfname: index file name or stdout
     */
}

fun decode(args: Array<String>) {
    /**
     * -o "expr" [ gzFName ] [ "idxfname.index" ]
     *  expr =  [min]..[max]
     *          [min]until<max>
     */

}
