@file:Suppress("UNCHECKED_CAST")

package vec.util

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

//@JvmOverloads
//fun horizon(
//    index: Int,
//    viewPoints: Int,
//    datapoints: Int,
//    dpDouble: Double = datapoints.toDouble(),
//    vpDouble: Double = viewPoints.toDouble(),
//): Int {
//    return max(
//        index,
//        (dpDouble - 1 - sin((vpDouble - index) / vpDouble * (PI / 2.0)) * dpDouble - 1).toInt()
//    )
//}
//
//@JvmOverloads
//fun hzInvSqr(index: Int, datapoints: Int, dpDub: Double = datapoints.toDouble(), vpDub: Double): Int = max(
//    index,
//    (1.0 / ((vpDub - index.toDouble()) * dpDub) * dpDub.pow(2.0)).toInt()
//)
//
