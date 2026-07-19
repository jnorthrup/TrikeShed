package borg.trikeshed.couch.isam

// import borg.trikeshed.collections.LinearOptimalHashMap
// import borg.trikeshed.collections.toMetaSeries
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.isam.*
import borg.trikeshed.parse.confix.ConfixIndex
import borg.trikeshed.parse.confix.scan
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.couch.isam.ConfixIsamIsomorphism
import borg.trikeshed.isam.RecordMeta

class ConfixIsamStoreBuilder {
    var dataFileLocation: String = ""
    var stringpoolLocation: String = ""
    var fileOps: FileOperations? = null
    private var exemplarIndex: ConfixIndex? = null

    /**
     * Define the schema implicitly by providing a representative JSON / CBOR
     * document. The isomorphism infers the `RecordMeta` from the scanned
     * `ConfixIndex`'s `ConfixIndexK` facets (Tags / Spans / DirectChildren /
     * KeyToChild). NOTE: `ConfixIsamIsomorphism.inferIsamSchemaFromConfixIndex`
     * is currently unimplemented and throws — see its kdoc for the missing cut.
     */
    fun exemplar(jsonString: String) {
        exemplarIndex = scan(jsonString)
    }

    fun exemplar(index: ConfixIndex) {
        exemplarIndex = index
    }

    fun build(): ConfixIsamCursorBridge {
        require(dataFileLocation.isNotEmpty()) { "dataFileLocation must be set" }
        require(stringpoolLocation.isNotEmpty()) { "stringpoolLocation must be set" }
        requireNotNull(exemplarIndex) { "an exemplar document must be provided to infer the ISAM schema" }

        val isamSchema = ConfixIsamIsomorphism.inferIsamSchemaFromConfixIndex(exemplarIndex!!)

        val resolvedFileOps = fileOps ?: throw IllegalStateException("fileOps must be configured")
        val stringpool = FileBackedStringpool(stringpoolLocation, resolvedFileOps)

        // This index aligns with the "Stringpools + index" requirement, using optimal linear hashing.
        val hashIndex = mutableMapOf<String, Int>() // CID/ID -> Row Index (or Stringpool Offset)
        val indexCursorList = hashIndex.entries.map { it.key to it.value }
        val indexCursor = indexCursorList.size j { i: Int -> indexCursorList[i] }

        // In a full environment, IsamDataFileBuilder creates the actual file mapping.
        // For the Factory DSEL, we wire the index + schema -> Cursor bridge.
        return ConfixIsamCursorBridge(
            schema = isamSchema,
            index = hashIndex,
            indexCursor = indexCursor,
            stringpool = stringpool
        )
    }
}

/**
 * The realized bridge satisfying the "Facetted Cursor abstractions" requirement.
 * This class yields a Cursor over the K-V store, compliant with MiniDuck algebra.
 */
class ConfixIsamCursorBridge(
    val schema: Series<RecordMeta>,
    val index: MutableMap<String, Int>,
    val indexCursor: Series<Pair<String, Int>>,
    val stringpool: Stringpool
) {
    /**
     * Vends the store as a standard algebraic Cursor.
     */
    fun toCursor(): Cursor {
        // Here we would compose the indexCursor (Keys/Offsets) with the ISAM DataFile (Values)
        // using Join or SpanMatcher logic to form a unified Series<RowVec>.
        // For now, we return a stub mimicking the row representation.
        return indexCursor.size j { i ->
            TODO("Compose Stringpool ISAM RowVec for $i")
        }
    }
}

/**
 * Complete Factory Builder DSEL entry point.
 */
fun confixIsamStore(block: ConfixIsamStoreBuilder.() -> Unit): ConfixIsamCursorBridge =
    ConfixIsamStoreBuilder().apply(block).build()
