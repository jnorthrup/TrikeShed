package cursors.io

import vec.macros.Join
import vec.macros.Series
import kotlin.coroutines.CoroutineContext

typealias CellMeta = () -> CoroutineContext
typealias RowVec = Series<Join<Any?, CellMeta>>
