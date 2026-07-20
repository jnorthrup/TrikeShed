#!/bin/bash
cat << 'MERGE' > /tmp/merge.diff
<<<<<<< SEARCH
    var stringpoolLocation: String = ""
    var fileOps: FileOperations? = null
    private var exemplarIndex: ConfixIndex? = null
=======
    var stringpoolLocation: String = ""
    var fileOps: FileOperations? = null
    var casStore: borg.trikeshed.job.CasStore? = null
    private var exemplarIndex: ConfixIndex? = null
>>>>>>> REPLACE
<<<<<<< SEARCH
        return ConfixIsamCursorBridge(
            schema = isamSchema,
            index = hashIndex,
            indexCursor = indexCursor,
            stringpool = stringpool
        )
=======
        return ConfixIsamCursorBridge(
            schema = isamSchema,
            index = hashIndex,
            indexCursor = indexCursor,
            stringpool = stringpool,
            casStore = casStore
        )
>>>>>>> REPLACE
<<<<<<< SEARCH
class ConfixIsamCursorBridge(
    val schema: Series<RecordMeta>,
    val index: MutableMap<String, Int>,
    val indexCursor: Series<Pair<String, Int>>,
    val stringpool: Stringpool
) {
=======
class ConfixIsamCursorBridge(
    val schema: Series<RecordMeta>,
    val index: MutableMap<String, Int>,
    val indexCursor: Series<Pair<String, Int>>,
    val stringpool: Stringpool,
    val casStore: borg.trikeshed.job.CasStore? = null
) {
>>>>>>> REPLACE
MERGE
patch -u src/commonMain/kotlin/borg/trikeshed/couch/isam/ConfixIsamFactory.kt -i /tmp/merge.diff
