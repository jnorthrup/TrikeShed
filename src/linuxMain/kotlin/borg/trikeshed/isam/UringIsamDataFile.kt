     1|package borg.trikeshed.isam//package borg.trikeshed.isam
     2|//
     3|//import borg.trikeshed.common.Usable
     4|//import borg.trikeshed.cursor.*
     5|//import borg.trikeshed.isam.meta.IOMemento
     6|//import borg.trikeshed.lib.*
     7|//import borg.trikeshed.native.HasPosixErr
     8|//import kotlinx.cinterop.*
     9|//import platform.posix.*
    10|//import platform.posix.FILE
    11|//import platform.posix.MAP_PRIVATE
    12|//import platform.posix.O_RDONLY
    13|//import platform.posix.PROT_READ
    14|//import platform.posix.close
    15|//import platform.posix.fopen
    16|//import platform.posix.mmap
    17|//import platform.posix.munmap
    18|//import platform.posix.open
    19|//import platform.posix.stat
    20|//import zlinux_uring.*
    21|//
    22|//fun <T> UringIsamDataFile.use(block: (UringIsamDataFile) -> T): T {
    23|//    val r = block(this)
    24|//    close()
    25|//    return r
    26|//}
    27|//
    28|//class UringIsamDataFile constructor(
    29|//    datafileFilename: String,
    30|//    metafileFilename: String,
    31|//    metafile: IsamMetaFileReader,
    32|//) : Usable,Cursor {
    33|//    val datafileFilename: String = datafileFilename
    34|//    val metafile: IsamMetaFileReader = metafile
    35|//
    36|//
    37|//    val recordlen: Int by lazy {
    38|//        this.metafile.recordlen.also {
    39|//            require(it > 0) { "recordlen must be > 0" }
    40|//        }
    41|//    } // unfortunately due to separation of ctor and open, this is not immutable
    42|//
    43|//    val constraints: Series<RecordMeta> by lazy { metafile.constraints }
    44|//    private lateinit var data: COpaquePointer
    45|//    var fileSize: Long = -1
    46|//
    47|//    private var first = true
    48|//    override fun open() {
    49|//        if (!first) return
    50|//        memScoped {
    51|//            val fd = open(datafileFilename, O_RDONLY)
    52|//            val stat = alloc<stat>()
    53|//            fstat(fd, stat.ptr)
    54|//            fileSize = stat.st_size.toLong()
    55|//
    56|//            require(fileSize % recordlen == 0L) { "fileSize must be a multiple of recordlen" }
    57|//
    58|//
    59|//            data = mmap(null, fileSize.toULong(), PROT_READ, MAP_PRIVATE, fd, 0)!!
    60|//            close(fd)
    61|//
    62|//            // report on record alignment of the file
    63|//            val alignment = fileSize % recordlen
    64|//            if (alignment != 0L) {
    65|//                println("WARN: file $datafileFilename is not aligned to recordlen $recordlen")
    66|//            } else
    67|//                println("DEBUG: file $datafileFilename is aligned to recordlen $recordlen")
    68|//
    69|//
    70|//            println("DEBUG: each record is ${recordlen.toLong().humanReadableByteCountIEC} bytes long")
    71|//
    72|//            // mention record counts and percentages of each field type by record byte occupancy and by file byte occupancy, and percentage of file
    73|//            val fieldCounts = mutableMapOf<IOMemento, Int>()
    74|//            val fieldOccupancy = mutableMapOf<IOMemento, Int>()
    75|//            constraints.forEach { constraint ->
    76|//                val count = fieldCounts.getOrPut(constraint.type) { 0 }
    77|//                fieldCounts[constraint.type] = count + 1
    78|//                val occupancy = fieldOccupancy.getOrPut(constraint.type) { 0 }
    79|//                fieldOccupancy[constraint.type] = occupancy + constraint.end - constraint.begin
    80|//            }
    81|//            val recordCount = fileSize / recordlen
    82|//            println("DEBUG: file  $datafileFilename has $recordCount records in ${fileSize.humanReadableByteCountIEC}")
    83|//            fieldCounts.forEach { (type, count) ->
    84|//                val occupancy = fieldOccupancy[type]!!
    85|//                println("DEBUG: file $datafileFilename has $count fields of type $type occupying $occupancy bytes (${occupancy * 100 / recordlen}%) of each record (${(occupancy * recordCount).humanReadableByteCountSI} in the file)")
    86|//            }
    87|//        }
    88|//    }
    89|//
    90|//    override val a: Int get() = open().let { return (fileSize / recordlen).toInt() }
    91|//    override val b: (Int) ->RowVec= { row ->
    92|//        memScoped {
    93|//            val d2 = data.toLong() + (row * recordlen)
    94|//
    95|//            constraints.size j { col ->
    96|//                constraints[col].let { recordMeta ->
    97|//                    val d4 = d2 + recordMeta.begin
    98|//                    val d5: COpaquePointer = d4.toCPointer()!!
    99|//                    val d6: ByteArray = d5.readBytes(recordMeta.end - recordMeta.begin)
   100|//                    recordMeta.decoder(d6)!! j { recordMeta }
   101|//                }
   102|//            }
   103|//        }
   104|//    }
   105|//
   106|//    override fun toString(): String =
   107|//        "IsamDataFile(metafile=$metafile, recordlen=$recordlen, constraints=$constraints, datafileFilename='$datafileFilename', fileSize=$fileSize)"
   108|//
   109|//    override fun close() {
   110|//        memScoped {
   111|//            munmap(data, fileSize.toULong())
   112|//        }
   113|//    }
   114|//
   115|//
   116|//    companion object {
   117|//
   118|//        /**exact same function but writes the file with IoUring set-up and writes and native heap */
   119|//        fun write (cursor: Cursor, datafilename: String, var): Unit {
   120|//            val metafilename = "$datafilename.meta"
   121|//            IsamMetaFileReader.write(metafilename, cursor.meta.map { columnMeta: ColumnMeta -> columnMeta as RecordMeta })
   122|//
   123|//
   124|//            // Create a file for writing
   125|//            val data: Int = open(datafilename, platform.posix.O_CREAT or platform.posix.O_WRONLY, 644.fromOctal())
   126|//
   127|//            //create row buffer
   128|//            val meta: Series<RecordMeta> = cursor.meta α { it as RecordMeta }
   129|//            val rowLen: Int = meta.last().end
   130|//            val rowBuffer = ByteArray(rowLen)
   131|//            val clears: IntArray =
   132|//                meta.view.withIndex().filter { it.value.type.networkSize == null }.map { it.index }.toIntArray()
   133|//
   134|//            // Create an io_uring instance and set up the queue
   135|//            val ring: io_uring = nativeHeap.alloc<io_uring>()
   136|//            HasPosixErr.posixRequires(io_uring_queue_init(32, ring.ptr, 0) == 0) { "io_uring_queue_init" }
   137|//
   138|//            // Create a submission queue entry
   139|//            val sqe: io_uring_sqe = nativeHeap.alloc<io_uring_sqe>()
   140|//            HasPosixErr.posixRequires(io_uring_get_sqe(ring.ptr) != null) { "io_uring_get_sqe" }
   141|//            //create  CValuesRef<CPointerVar<io_uring_cqe>> to pass to io_uring_wait_cqe()
   142|//
   143|//
   144|//            //do we need to pre-create cqe here also? or is it created by the io_uring_wait_cqe() call?
   145|//            val cqe: io_uring_cqe = nativeHeap.alloc<io_uring_cqe>()
   146|//            memScoped {
   147|//                val cPointerVarOfCqe = alloc<CPointerVar<io_uring_cqe>> { cqe.ptr }
   148|//
   149|//
   150|//                //write rows
   151|//                for (y in 0 until cursor.a) {
   152|//                    val rowData: Series<Any> = cursor.row(y).left
   153|//
   154|//                    for (x in 0 until cursor.meta.size) {
   155|//                        val colMeta: RecordMeta = meta[x]
   156|//                        val colData: Any? = rowData[x]
   157|//                        val colBytes: ByteArray = colMeta.encoder(colData)
   158|//                        colBytes.copyInto(rowBuffer, colMeta.begin, 0, colBytes.size)
   159|//                        if (x in clears && colBytes.size < colMeta.end - colMeta.begin)   //write 1 zero
   160|//                            rowBuffer[colMeta.begin + colBytes.size] = 0
   161|//                    }
   162|//
   163|//                    //set up the io_uring_sqe
   164|//                    val offset: ULong = (y * rowLen).toULong()
   165|//                    io_uring_prep_write(sqe.ptr, data, rowBuffer.refTo(0), rowLen.toUInt(), offset)
   166|//
   167|//                    //submit the io_uring_sqe
   168|//                    HasPosixErr.posixRequires(io_uring_submit(ring.ptr) == 1) { "io_uring_submit" }
   169|//
   170|//
   171|//                    //wait for the io_uring_cqe
   172|//                    HasPosixErr.posixRequires(
   173|//                        io_uring_wait_cqe(
   174|//                            ring.ptr,
   175|//                            cPointerVarOfCqe.ptr
   176|//                        ) == 0
   177|//                    ) { "io_uring_wait_cqe" }
   178|//
   179|//                    //check the result
   180|//                    HasPosixErr.posixRequires(cqe.res == rowLen) { "io_uring_wait_cqe" }
   181|//
   182|//                    //free the io_uring_cqe
   183|//                    io_uring_cqe_seen(ring.ptr, cqe.ptr)
   184|//                }
   185|//            }
   186|//
   187|//            //close the io_uring
   188|//            io_uring_queue_exit(ring.ptr)
   189|//
   190|//            //free the native heap
   191|//            nativeHeap.free(ring)
   192|//            nativeHeap.free(sqe)
   193|//            nativeHeap.free(cqe)
   194|//
   195|//
   196|//            //close the file
   197|//            close(data)
   198|//
   199|//        }
   200|//    }
   201|//}
   202|//
   203|//
   204|//
   205|