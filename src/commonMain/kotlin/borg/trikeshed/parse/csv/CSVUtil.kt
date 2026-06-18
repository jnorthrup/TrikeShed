/** list<String>  -> CSV Cursor of strings
 * */
@OptIn(ExperimentalUnsignedTypes::class)
fun simpelCsvCursor(lineList: List<String>): Cursor {
    //take line11 as headers.  the split by ','
    val headerNames = lineList[0].split(",").map { it.trim() }
    val hdrMeta = headerNames.map { RecordMeta(it, IoString) }
    //count of fields
    val fieldCount = headerNames.size
    val lines = lineList.drop(1)
    val lineSegments = arrayOfNulls<UShortArray>(lines.size)

    return lines.size j { y ->
        val line = lines[y]
        //lazily create linesegs
        val lineSegs = lineSegments[y] ?: UShortArray(headerNames.size).also { proto ->
            lineSegments[y] = proto
            var f = 0
            for ((x, c) in line.withIndex()) if (c == ',')
                proto[f++] = x.toUShort()
        }

        fieldCount j { x: Int ->
            val start = if (x == 0) 0 else lineSegs[x - 1].toInt() + 1
            val end = if (x == fieldCount - 1) line.length else lineSegs[x].toInt()
            // Cast value to Any? to match RowVec = Series2<Any?, ColumnMeta↻>
            // hdrMeta[x].leftIdentity supplies () -> RecordMeta which IS a ColumnMeta via delegation
            line.substring(start, end) as Any? j hdrMeta[x].leftIdentity
        }
    }
}