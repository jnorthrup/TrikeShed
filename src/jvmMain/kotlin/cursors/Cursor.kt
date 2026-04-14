@file:Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")

package cursors


 typealias Cursor=  borg.trikeshed.cursor.Cursor


//
//infix fun Cursor.at(y: Int): RowVec = second(if (y < 0) size - y else y)
//
//infix fun Cursor.at(r: IntRange): Cursor = r.last - r.first t2 { y -> second(y + r.first) }
//
//@Suppress("USELESS_CAST")
//operator fun Cursor.get(i1:Int,i2:Int,vararg index: Int): Cursor {
//    val join = s_[s_[i1, i2]
//    return get(join)
//}
//
//operator fun Cursor.get(indexes: Iterable<Int>): Cursor = this[indexes.toList().toIntArray()]
//
//operator fun Cursor.get(index: IntArray): Cursor =
//    size t2 { y: Int ->
//        index.size t2 { x: Int ->
//            row(y)[index[x]]
//        }
//    }
//
//operator fun Cursor.get(index: IntRange): Cursor = this[index.toList().toIntArray()]
//
//infix fun Cursor.row(y: Int): RowVec = at(y)
//
//operator fun Cursor.unaryMinus(): Series<Series<*>> = this α { row -> row α { cell -> cell.first } }
//
//infix operator fun <T> Cursor.div(t: Class<T>): Series<Series<T?>> =
//    (-this) α { outer -> outer α { inner -> inner as? T } }
