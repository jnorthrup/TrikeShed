package org.xvm.cursor

import borg.trikeshed.lib.ChunkedMutableSeries
import borg.trikeshed.lib.EvictionListener
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.MutableSeries
import borg.trikeshed.lib.Reducer
import borg.trikeshed.lib.ReduxMutableSeries
import borg.trikeshed.lib.RingSeries
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.toSeries
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.properties.Delegates
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.TypeMemento
import borg.trikeshed.lib.j

typealias ByteSeries = Series<Byte>

class ColumnMetaRef(
    val ordinal: Int,
    override val name: String,
    val typeName: String,
    val facet: PointcutFacet = PointcutFacet.Unfaceted,
) : ColumnMeta {
    // ColumnMeta = Join<CharSequence, Join<TypeMemento, ColumnMeta?>>
    override val a: CharSequence get() = name
    override val b: Join<TypeMemento, ColumnMeta?> get() = IOMemento.IoObject j null

    fun copy(
        ordinal: Int = this.ordinal,
        name: String = this.name,
        typeName: String = this.typeName,
        facet: PointcutFacet = this.facet,
    ) = ColumnMetaRef(ordinal, name, typeName, facet)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ColumnMetaRef) return false
        return ordinal == other.ordinal && name == other.name && typeName == other.typeName && facet == other.facet
    }

    override fun hashCode(): Int = 31 * (31 * (31 * ordinal + name.hashCode()) + typeName.hashCode()) + facet.hashCode()
    override fun toString(): String = "ColumnMetaRef($ordinal, $name, $typeName, $facet)"
}

enum class PointcutFacet {
    Unfaceted,
    SymbolName,
    TypeInfo,
    ClassfileCoordinate,
    XvmCoordinate,
    PointcutKind,
    StringPool,
    Wireproto,
    ChildRows,
    ConfixMeta,
    VmStats,
    ObserverDelegateRegistration,
    ReduxPhilum,
    SynapsePhilum,
    ClassfileTaxonomy,
    EdgeTaxonomy,
    CrmsDomain,
    XSrcFile,
}

data class MemSegment(
    val bytes: ByteArray,
) {
    fun reifier(order: ByteOrder = ByteOrder.BIG_ENDIAN): ByteBuffer = ByteBuffer.wrap(bytes).order(order)
}

data class PooledVarcharHashRow(
    val signatureIndex: Int,
    val ordinal: Int,
    val metaNameHash: Int,
    val metaTypeHash: Int,
    val valueHash: Int,
)

class PooledVarcharCursor(
    private val rows: Series<PooledVarcharHashRow>,
) : Join<Int, (Int) -> PooledVarcharHashRow> {
    override val a: Int
        get() = rows.a

    override val b: (Int) -> PooledVarcharHashRow
        get() = { index -> rows.b(index) }

    fun byMetaNameHash(hash: Int): Series<PooledVarcharHashRow> {
        val matches = IntArray(rows.a)
        var count = 0
        for (index in 0 until rows.a) {
            if (rows.b(index).metaNameHash == hash) {
                matches[count++] = index
            }
        }
        return seriesRef(count) { index -> rows.b(matches[index]) }
    }
}

class PooledVarcharJointTable {
    private val rows = ChunkedMutableSeries<PooledVarcharHashRow>()

    fun <T> append(signatureIndex: Int, signature: Join<Series<T>, ColumnMetaRef>) {
        val metaNameHash = StringPool.intern(signature.b.name)
        val metaTypeHash = StringPool.intern(signature.b.typeName)
        for (valueIndex in 0 until signature.a.a) {
            rows.add(
                PooledVarcharHashRow(
                    signatureIndex = signatureIndex,
                    ordinal = signature.b.ordinal,
                    metaNameHash = metaNameHash,
                    metaTypeHash = metaTypeHash,
                    valueHash = StringPool.intern(signature.a.b(valueIndex).toString()),
                ),
            )
        }
    }

    fun cursor(): PooledVarcharCursor = PooledVarcharCursor(mutableSeriesRef(rows))
}

data class WireDelivery<T>(
    val signature: Join<Series<T>, ColumnMetaRef>,
    val wireproto: ByteArray,
    val proxy: Lazy<Join<Series<T>, ColumnMetaRef>>,
)

private data class JoinRef<A, B>(
    override val a: A,
    override val b: B,
) : Join<A, B>

private fun <T> seriesRef(size: Int, access: (Int) -> T): Series<T> = JoinRef(size, access)

private fun <T> mutableSeriesRef(series: MutableSeries<T>): Series<T> = seriesRef(series.a) { index ->
    series.b(index)
}

class WireSeries<T>(
    private val codec: CodecPlatform<T>,
) {
    interface CodecPlatform<T> {
        val byteOrder: ByteOrder
            get() = ByteOrder.BIG_ENDIAN

        fun encodeRecord(record: ByteBuffer, signature: Join<Series<T>, ColumnMetaRef>)
        fun decodeRecord(record: ByteBuffer): Join<Series<T>, ColumnMetaRef>
    }

    fun singleWireproto(signature: Join<Series<T>, ColumnMetaRef>): ByteArray {
        val record = encodeRecord(signature)
        val wire = ByteBuffer.allocate(4 + 4 + record.size).order(codec.byteOrder)
        wire.putInt(1)
        wire.putInt(record.size)
        wire.put(record)
        return wire.array()
    }

    fun drain(signatures: Series<Join<Series<T>, ColumnMetaRef>>): ByteArray {
        val count = signatures.a
        val records = ArrayList<ByteArray>(count)
        var total = 4
        for (index in 0 until count) {
            val encoded = encodeRecord(signatures.b(index))
            records += encoded
            total += 4 + encoded.size
        }
        val wire = ByteBuffer.allocate(total).order(codec.byteOrder)
        wire.putInt(count)
        for (record in records) {
            wire.putInt(record.size)
            wire.put(record)
        }
        return wire.array()
    }

    fun decode(bytes: ByteArray): Series<Join<Series<T>, ColumnMetaRef>> {
        val segment = MemSegment(bytes)
        val buf = segment.reifier(codec.byteOrder)
        val count = buf.int
        val offsets = IntArray(count)
        for (index in 0 until count) {
            offsets[index] = buf.position()
            val recordLen = buf.int
            buf.position(buf.position() + recordLen)
        }
        val offsetSeries = offsets.toSeries()
        return seriesRef(offsetSeries.a) { index ->
            val recordOffset = offsetSeries.b(index)
            val dup = segment.reifier(codec.byteOrder)
            dup.position(recordOffset)
            val recordLen = dup.int
            val recordBytes = ByteArray(recordLen)
            dup.get(recordBytes)
            codec.decodeRecord(ByteBuffer.wrap(recordBytes).order(codec.byteOrder))
        }
    }

    private fun encodeRecord(signature: Join<Series<T>, ColumnMetaRef>): ByteArray {
        val size = codec.recordSize(signature)
        val record = ByteBuffer.allocate(size).order(codec.byteOrder)
        codec.encodeRecord(record, signature)
        return record.array()
    }

    companion object {
        fun strings(): WireSeries<String> = WireSeries(StringCodecPlatform)
    }
}

private fun <T> WireSeries.CodecPlatform<T>.recordSize(signature: Join<Series<T>, ColumnMetaRef>): Int {
    val values = signature.a
    var total = 4
    total += 4 + signature.b.name.toByteArray(Charsets.UTF_8).size
    total += 4 + signature.b.typeName.toByteArray(Charsets.UTF_8).size
    total += 4
    for (index in 0 until values.a) {
        total += valueSize(values.b(index))
    }
    return total
}

private fun WireSeries.CodecPlatform<String>.valueSize(value: String): Int =
    4 + value.toByteArray(Charsets.UTF_8).size

@Suppress("UNCHECKED_CAST")
private fun <T> WireSeries.CodecPlatform<T>.valueSize(value: T): Int = when (this) {
    StringCodecPlatform -> StringCodecPlatform.valueSize(value as String)
    else -> error("Unsupported codec platform: ${this::class.java.name}")
}

object StringCodecPlatform : WireSeries.CodecPlatform<String> {
    override fun encodeRecord(record: ByteBuffer, signature: Join<Series<String>, ColumnMetaRef>) {
        record.putInt(signature.b.ordinal)
        writeString(record, signature.b.name)
        writeString(record, signature.b.typeName)
        record.putInt(signature.a.a)
        for (index in 0 until signature.a.a) {
            writeString(record, signature.a.b(index))
        }
    }

    override fun decodeRecord(record: ByteBuffer): Join<Series<String>, ColumnMetaRef> {
        val ordinal = record.int
        val name = readString(record)
        val type = readString(record)
        val count = record.int
        val offsets = IntArray(count)
        for (index in 0 until count) {
            offsets[index] = record.position()
            val len = record.int
            record.position(record.position() + len)
        }
        val segment = MemSegment(record.array())
        val valueSeries = seriesRef(count) { index ->
            val buf = segment.reifier(ByteOrder.BIG_ENDIAN)
            buf.position(offsets[index])
            readString(buf)
        }
        return JoinRef(valueSeries, ColumnMetaRef(ordinal, name, type))
    }

    private fun writeString(record: ByteBuffer, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        record.putInt(bytes.size)
        record.put(bytes)
    }

    private fun readString(record: ByteBuffer): String {
        val len = record.int
        val bytes = ByteArray(len)
        record.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}

class EventSeries<T>(
    initial: Series<T>,
    private val metaProvider: () -> ColumnMetaRef,
) {
    constructor(initial: Series<T>, meta: ColumnMetaRef) : this(initial, { meta })

    private val listeners = ArrayList<(Join<Series<T>, ColumnMetaRef>) -> Unit>()

    var series: Series<T> by Delegates.observable(initial) { _, _, next ->
        val signature = JoinRef(next, metaProvider())
        for (listener in listeners) {
            listener(signature)
        }
    }

    val signature: Join<Series<T>, ColumnMetaRef>
        get() = JoinRef(series, metaProvider())

    fun subscribe(listener: (Join<Series<T>, ColumnMetaRef>) -> Unit) {
        listeners += listener
    }
}

class BatchingSeries<T>(
    capacity: Int = 256,
    private val wireSeries: WireSeries<String> = WireSeries.strings(),
) {
    private val signatures = ChunkedMutableSeries<Join<Series<T>, ColumnMetaRef>>()
    private val deliveries = ChunkedMutableSeries<WireDelivery<T>>()
    private val evicted = ChunkedMutableSeries<WireDelivery<T>>()
    private val jointTable = PooledVarcharJointTable()
    private val hot = RingSeries(capacity, object : EvictionListener<WireDelivery<T>> {
        override fun onEvict(value: WireDelivery<T>) {
            evicted.add(value)
        }
    })
    private val reducer = object : Reducer<Join<Series<T>, ColumnMetaRef>, Int> {
        override val zero: Int = 0
        override fun combine(acc: Int, element: Join<Series<T>, ColumnMetaRef>): Int = acc + 1
    }
    private val capture = JoinRef(emptySeriesOf<T>(), ColumnMetaRef(-1, "", ""))
    val journal = ReduxMutableSeries(signatures, reducer, 0, capture)

    fun accept(signature: Join<Series<T>, ColumnMetaRef>) {
        journal.add(signature)
        val signatureIndex = signatures.a - 1
        jointTable.append(signatureIndex, signature)
        val wireproto = wireSeries.singleWireproto(signature.asWireSignature())
        val delivery = WireDelivery(signature, wireproto, lazy { signature })
        deliveries.add(delivery)
        hot.add(delivery)
    }

    fun signatures(): Series<Join<Series<T>, ColumnMetaRef>> = mutableSeriesRef(signatures)

    fun deliveries(): Series<WireDelivery<T>> = mutableSeriesRef(deliveries)

    fun evicted(): Series<WireDelivery<T>> = mutableSeriesRef(evicted)

    fun cursor(): PooledVarcharCursor = jointTable.cursor()

    fun drainToWireproto(): ByteArray = wireSeries.drain(signatures().asWireSignatures())

    fun singleWireproto(signature: Join<Series<T>, ColumnMetaRef>): ByteArray = wireSeries.singleWireproto(signature.asWireSignature())
}

private fun <T> Join<Series<T>, ColumnMetaRef>.asWireSignature(): Join<Series<String>, ColumnMetaRef> = JoinRef(
    seriesRef(a.a) { index -> a.b(index).toString() },
    b,
)

private fun <T> Series<Join<Series<T>, ColumnMetaRef>>.asWireSignatures(): Series<Join<Series<String>, ColumnMetaRef>> =
    seriesRef(a) { index -> b(index).asWireSignature() }

class BatchMutableSeries<T>(
    private val batching: BatchingSeries<T> = BatchingSeries(256),
) : Join<Int, (Int) -> Join<Series<T>, ColumnMetaRef>> {
    override val a: Int
        get() = batching.signatures().a

    override val b: (Int) -> Join<Series<T>, ColumnMetaRef>
        get() = { index -> batching.signatures().b(index) }

    fun attach(eventSeries: EventSeries<T>) {
        eventSeries.subscribe { signature ->
            batching.accept(signature)
        }
    }

    fun deliveries(): Series<WireDelivery<T>> = batching.deliveries()

    fun cursor(): PooledVarcharCursor = batching.cursor()

    fun drainToWireproto(): ByteArray = batching.drainToWireproto()

    fun singleWireproto(signature: Join<Series<T>, ColumnMetaRef>): ByteArray = batching.singleWireproto(signature)
}

object PointcutWireSpine {
    @Volatile
    private var bootstrapped = false

    data class LaunchMeta(
        val className: String,
        val codeSource: URL?,
    )

    @Volatile
    var launchMeta: LaunchMeta? = null
        private set

    fun bootstrapOnLaunch(anchor: Class<*> = PointcutWireSpine::class.java): LaunchMeta {
        if (!bootstrapped) {
            synchronized(this) {
                if (!bootstrapped) {
                    PointcutRegistry.installDefaults()
                    launchMeta = LaunchMeta(anchor.name, anchor.protectionDomain?.codeSource?.location)
                    bootstrapped = true
                }
            }
        }
        return launchMeta ?: LaunchMeta(anchor.name, null)
    }
}
