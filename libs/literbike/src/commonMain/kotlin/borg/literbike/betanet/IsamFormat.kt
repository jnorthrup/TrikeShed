package borg.literbike.betanet

/**
 * ISAM Format - Exact port of Kotlin ISAM format.
 * Provides binary-compatible file format with network-endian encoding.
 * Ported from literbike/src/betanet/isam_format.rs.
 */

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * IOMemento - runtime type representation with precise network sizes.
 */
enum class IOMemento(
    val ordinal: Int,
    private val networkSize: Int?
) {
    IoBoolean(0, 1),
    IoByte(1, 1),
    IoInt(2, 4),
    IoLong(3, 8),
    IoFloat(4, 4),
    IoDouble(5, 8),
    IoString(6, null), // Variable size
    IoLocalDate(7, 8),
    IoInstant(8, 12),
    IoNothing(9, 0);

    companion object {
        fun fromName(name: String): IOMemento? = when (name) {
            "IoBoolean" -> IoBoolean
            "IoByte" -> IoByte
            "IoInt" -> IoInt
            "IoLong" -> IoLong
            "IoFloat" -> IoFloat
            "IoDouble" -> IoDouble
            "IoString" -> IoString
            "IoLocalDate" -> IoLocalDate
            "IoInstant" -> IoInstant
            "IoNothing" -> IoNothing
            else -> null
        }
    }

    fun networkSize(): Int? = networkSize

    fun name(): String = this.toString()
}

/**
 * Scalar type with IOMemento and optional string description.
 */
data class Scalar(
    val ioMemento: IOMemento,
    val description: String? = null
)

/**
 * Network coordinate pair (start, end) for column layout.
 */
typealias NetworkCoord = Pair<Int, Int>

/**
 * Calculate network coordinates from IOMemento types.
 */
fun networkCoords(
    ioMementos: List<IOMemento>,
    defaultVarcharSize: Int = 128,
    varcharSizes: Map<Int, Int>? = null
): List<NetworkCoord> {
    val sizes = networkSizes(ioMementos, defaultVarcharSize, varcharSizes)
    var recordLen = 0
    val coords = mutableListOf<NetworkCoord>()
    for (size in sizes) {
        val start = recordLen
        val end = recordLen + size
        coords.add(start to end)
        recordLen = end
    }
    return coords
}

/**
 * Calculate network sizes for each column.
 */
fun networkSizes(
    ioMementos: List<IOMemento>,
    defaultVarcharSize: Int = 128,
    varcharSizes: Map<Int, Int>? = null
): List<Int> {
    return ioMementos.mapIndexed { index, memento ->
        memento.networkSize() ?: varcharSizes?.get(index) ?: defaultVarcharSize
    }
}

/**
 * Write ISAM meta file with exact format.
 * Format:
 * Line 1: Space-separated coordinate pairs
 * Line 2: Space-separated column names
 * Line 3: Space-separated IOMemento names
 */
fun writeIsamMeta(
    pathname: String,
    coords: List<NetworkCoord>,
    columnNames: List<String>,
    ioMementos: List<IOMemento>
): Result<Unit> {
    return runCatching {
        val metaPath = "$pathname.meta"
        BufferedWriter(FileWriter(metaPath)).use { writer ->
            writer.write("# format:  coords WS .. EOL names WS .. EOL TypeMememento WS ..")
            writer.newLine()
            writer.write("# last coord is the recordlen")
            writer.newLine()

            // Line 1: Coordinates flattened
            val coordLine = coords.flatMap { (start, end) -> listOf(start.toString(), end.toString()) }
            writer.write(coordLine.joinToString(" "))
            writer.newLine()

            // Line 2: Column names with spaces replaced by underscores
            val nameLine = columnNames.map { it.replace(' ', '_') }
            writer.write(nameLine.joinToString(" "))
            writer.newLine()

            // Line 3: IOMemento names
            val mementoLine = ioMementos.map { it.name() }
            writer.write(mementoLine.joinToString(" "))
            writer.newLine()
        }
    }
}

/**
 * Read ISAM meta file and parse coordinates, names, and types.
 */
fun readIsamMeta(metaPath: String): Result<Triple<List<NetworkCoord>, List<String>, List<IOMemento>>> {
    return runCatching {
        val lines = BufferedReader(FileReader(metaPath)).useLines { seq ->
            seq.filter { !it.startsWith("# ") && it.trim().isNotEmpty() }.toList()
        }

        if (lines.size < 3) {
            throw IOException("Meta file must have at least 3 non-comment lines")
        }

        // Parse coordinates from line 1
        val coordNums = lines[0].split(Regex("\\s+")).map { it.toInt() }
        if (coordNums.size % 2 != 0) {
            throw IOException("Coordinate count must be even (start/end pairs)")
        }
        val coords = coordNums.chunked(2).map { (start, end) -> start to end }

        // Parse column names from line 2
        val names = lines[1].split(Regex("\\s+"))

        // Parse IOMemento types from line 3
        val mementos = lines[2].split(Regex("\\s+")).map { name ->
            IOMemento.fromName(name) ?: throw IOException("Unknown IOMemento: $name")
        }

        Triple(coords, names, mementos)
    }
}

/**
 * Cell data value matching Kotlin Any? semantics.
 */
sealed class CellValue {
    data class BooleanVal(val value: Boolean) : CellValue()
    data class ByteVal(val value: Byte) : CellValue()
    data class IntVal(val value: Int) : CellValue()
    data class LongVal(val value: Long) : CellValue()
    data class FloatVal(val value: Float) : CellValue()
    data class DoubleVal(val value: Double) : CellValue()
    data class StringVal(val value: String) : CellValue()
    data class LocalDate(val days: Long) : CellValue() // Days since Unix epoch
    data class Instant(val seconds: Long, val nanos: Int) : CellValue()
    object NothingVal : CellValue()

    companion object {
        fun nowInstant(): Instant {
            val now = System.currentTimeMillis()
            return Instant(now / 1000, (now % 1000 * 1_000_000).toInt())
        }
    }

    /**
     * Write value to buffer using network-endian encoding.
     */
    fun writeToBuffer(buffer: ByteBuffer, memento: IOMemento, varcharSize: Int? = null) {
        when (this) {
            is BooleanVal -> buffer.put(if (value) 1 else 0)
            is ByteVal -> buffer.put(value)
            is IntVal -> buffer.putInt(value)
            is LongVal -> buffer.putLong(value)
            is FloatVal -> buffer.putFloat(value)
            is DoubleVal -> buffer.putDouble(value)
            is StringVal -> {
                val size = varcharSize ?: 128
                val bytes = value.toByteArray()
                val writeLen = minOf(bytes.size, size)
                buffer.put(bytes, 0, writeLen)
                repeat(size - writeLen) { buffer.put(32) } // SPACE byte
            }
            is LocalDate -> buffer.putLong(days)
            is Instant -> {
                buffer.putLong(seconds)
                buffer.putInt(nanos)
            }
            is NothingVal -> {}
        }
    }
}

/**
 * Row of cell values with scalars.
 */
data class RowVec(
    val cells: List<CellValue>,
    val scalars: List<Scalar>
) {
    fun size(): Int = cells.size
}

/**
 * Cursor trait for ISAM writing.
 */
interface Cursor {
    fun size(): Int
    fun getRow(index: Int): RowVec?
    fun scalars(): List<Scalar>
}

/**
 * Write cursor data to ISAM format.
 */
fun writeIsam(
    cursor: Cursor,
    pathname: String,
    defaultVarcharSize: Int = 128,
    varcharSizes: Map<Int, Int>? = null
): Result<Unit> {
    return runCatching {
        val scalars = cursor.scalars()
        val ioMementos = scalars.map { it.ioMemento }
        val columnNames = scalars.map { it.description ?: "unknown" }

        // Calculate network coordinates
        val coords = networkCoords(ioMementos, defaultVarcharSize, varcharSizes)
        val recordLen = coords.lastOrNull()?.second ?: 0

        // Write meta file
        writeIsamMeta(pathname, coords, columnNames, ioMementos).getOrThrow()

        // Write binary data
        DataOutputStream(BufferedOutputStream(FileOutputStream(pathname))).use { dos ->
            for (rowIndex in 0 until cursor.size()) {
                val row = cursor.getRow(rowIndex) ?: continue
                val buffer = ByteBuffer.allocate(recordLen)

                for ((colIndex, cell) in row.cells.withIndex()) {
                    val memento = ioMementos[colIndex]
                    val varcharSize = if (memento == IOMemento.IoString) {
                        varcharSizes?.get(colIndex)
                    } else {
                        null
                    }
                    cell.writeToBuffer(buffer, memento, varcharSize)
                }

                // Ensure row is exactly recordLen bytes
                while (buffer.position() < recordLen) {
                    buffer.put(0)
                }

                dos.write(buffer.array())
            }
        }
    }
}
