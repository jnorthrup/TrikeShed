package cursors.macros

import cursors.Cursor
import cursors.io.RowVec
import cursors.io.scalars
import cursors.io.CellMeta
import vec.macros.*
//
//fun join(vargs: Series<Cursor>): Cursor {
//    val cursorArray = Array(vargs.size) { i -> vargs[i] }
//    return join(*cursorArray)
//}
//
//fun join(vararg vargs: Cursor): Cursor {
//    if (vargs.isEmpty()) {
//        throw IllegalArgumentException("Cannot join an empty list of cursors")
//    }
//    if (vargs.size == 1) {
//        return vargs[0]
//    }
//
//    return muxIndexes(vargs.map(Cursor::scalars)).let { (totalWidth, tails) ->
//        val numRows = vargs[0].size
//
//        object : Cursor {
//            override val first: Int
//                get() = numRows
//
//            override val second: (Int) -> RowVec
//                get() = { rowIndex: Int ->
//                    object : RowVec {
//                        override val first: Int
//                            get() = totalWidth
//
//                        override val second: (Int) -> Pai2<Any?, CellMeta>
//                            get() = { columnIndex: Int ->
//                                val (sourceCursorIndex, indexInSourceCursor) = demuxIndex(tails, columnIndex)
//                                val sourceCursor = vargs[sourceCursorIndex]
//                                val sourceRow = sourceCursor.second(rowIndex)
//                                val cell = sourceRow.second(indexInSourceCursor)
//                                Pai2(cell.first, cell.second)
//                            }
//                    }
//                }
//        }
//    }
//}
//
