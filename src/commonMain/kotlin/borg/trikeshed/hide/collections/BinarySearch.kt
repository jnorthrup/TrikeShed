package borg.trikeshed.common.collections

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size


//binary search
fun <T : Comparable<T>> Series<T>.binarySearch(element: T, low: Int = 0, high: Int = size - 1): Int =
    binarySearch(element, naturalOrder(), low, high)


// Tail-recursive binary search
tailrec fun <T: Comparable<T>> Series<T>.binarySearch(element: T, c: Comparator<T>, low: Int = 0, high: Int = size - 1): Int {
    if (low > high) return -(low + 1)  // key not found

    val mid = (low + high) ushr 1
    val cmp = c.compare(get(mid), element)
    return when {
        cmp < 0 -> binarySearch(element, c, mid.inc(), high)
        cmp > 0 -> binarySearch(element, c, low, mid.dec())
        else -> mid  // key found
    }
}

//signed, unsigned primitives all need boilerplate here
fun Series<Byte>.binarySearch(element: Byte, low_: Int = 0, high_: Int = size - 1): Int {
    var low = low_
    var high = high_

    while (low <= high) {
        val mid = (low + high).ushr(1)
        val midVal = get(mid)
        /** test for equality, then compare */
        when {
            midVal < element -> low = mid + 1
            midVal > element -> high = mid - 1
            else -> return mid // key found
        }

    }
    return -(low + 1)  // key not found.
}

fun Series<Short>.binarySearch(element: Short, low_: Int = 0, high_: Int = size - 1): Int {
    var low = low_
    var high = high_

    while (low <= high) {
        val mid = (low + high).ushr(1)
        val midVal = get(mid)
        /** test for equality, then compare */
        when {
            midVal < element -> low = mid + 1
            midVal > element -> high = mid - 1
            else -> return mid // key found
        }
    }
    return -(low + 1)  // key not found.
}

tailrec fun Series<Int>.binarySearch(element: Int, low: Int = 0, high: Int = size - 1): Int {
    if (low > high) return -(low + 1)

    val mid = (low + high) ushr 1
    val midVal = get(mid)
    return when {
        midVal < element -> binarySearch(element, mid + 1, high)
        midVal > element -> binarySearch(element, low, mid - 1)
        else -> mid
    }
}
fun Series<Long>.binarySearch(element: Long, low_: Int = 0, high_: Int = size - 1): Int {
    var low = low_
    var high = high_

    while (low <= high) {
        val mid = (low + high).ushr(1)
        val midVal = get(mid)
        /** test for equality, then compare */
        when {
            midVal < element -> low = mid + 1
            midVal > element -> high = mid - 1
            else -> return mid // key found
        }

    }
    return -(low + 1)  // key not found.
}

fun Series<Float>.binarySearch(element: Float, low_: Int = 0, high_: Int = size - 1): Int {
    var low = low_
    var high = high_

    while (low <= high) {
        val mid = (low + high).ushr(1)
        val midVal = get(mid)
        /** test for equality, then compare */
        when {
            midVal < element -> low = mid + 1
            midVal > element -> high = mid - 1
            else -> return mid // key found
        }

    }
    return -(low + 1)  // key not found.
}

fun Series<Double>.binarySearch(element: Double, low_: Int = 0, high_: Int = size - 1): Int {
    var low = low_
    var high = high_

    while (low <= high) {
        val mid = (low + high).ushr(1)
        val midVal = get(mid)
        /** test for equality, then compare */
        when {
            midVal < element -> low = mid + 1
            midVal > element -> high = mid - 1
            else -> return mid // key found
        }

    }
    return -(low + 1)  // key not found.
}

fun Series<Char>.binarySearch(element: Char, low_: Int = 0, high_: Int = size - 1): Int {
    var low = low_
    var high = high_

    while (low <= high) {
        val mid = (low + high).ushr(1)
        val midVal = get(mid)
        /** test for equality, then compare */
        when {
            midVal < element -> low = mid + 1
            midVal > element -> high = mid - 1
            else -> return mid // key found
        }

    }
    return -(low + 1)  // key not found.
}

//ubyte, then ...
fun Series<UByte>.binarySearch(element: UByte, low_: Int = 0, high_: Int = size - 1): Int {
    var low = low_
    var high = high_

    while (low <= high) {
        val mid = (low + high).ushr(1)
        val midVal = get(mid)
        /** test for equality, then compare */
        when {
            midVal < element -> low = mid + 1
            midVal > element -> high = mid - 1
            else -> return mid // key found
        }

    }
    return -(low + 1)  // key not found.
}

fun Series<UShort>.binarySearch(element: UShort, low_: Int = 0, high_: Int = size - 1): Int {
    var low = low_
    var high = high_

    while (low <= high) {
        val mid = (low + high).ushr(1)
        val midVal = get(mid)
        /** test for equality, then compare */
        when {
            midVal < element -> low = mid + 1
            midVal > element -> high = mid - 1
            else -> return mid // key found
        }

    }
    return -(low + 1)  // key not found.
}

fun Series<UInt>.binarySearch(element: UInt, low_: Int = 0, high_: Int = size - 1): Int {
    var low = low_
    var high = high_

    while (low <= high) {
        val mid = (low + high).ushr(1)
        val midVal = get(mid)
        when {
            midVal < element -> low = mid + 1
            midVal > element -> high = mid - 1
            else -> return mid // key found
        }
    }
    return -(low + 1)  // key not found.
}

/**
when the index is exact match, return the index
when the index is not exact match, return the negative value of the index of the next element
 */
fun Series<ULong>.binarySearch(element: ULong, low_: Int = 0, high_: Int = size - 1): Int {
    var low = low_
    var high = high_

    while (low <= high) {
        val mid = (low + high).ushr(1)
        val midVal = get(mid)
        /** test for equality, then compare */
        when {
            midVal < element -> low = mid + 1
            midVal > element -> high = mid - 1
            else -> return mid // key found
        }
    }
    return -(low + 1)  // key not found.
}

fun <T> Array<out T>.binarySearch(
    element: T,
    comparator: Comparator<in T>,
    fromIndex: Int = 0,
    toIndex: Int = size
): Int {
    var low = fromIndex
    var high = toIndex - 1

    while (low <= high) {
        val mid = (low + high).ushr(1)
        val midVal = get(mid)
        val cmp = comparator.compare(midVal, element)
        /** test for equality, then compare */
        when {
            cmp < 0 -> low = mid + 1
            cmp > 0 -> high = mid - 1
            else -> return mid // key found
        }
    }
    return -(low + 1)  // key not found.
}

fun <T : Comparable<T>> Array<out T>.binarySearch(
    element: T,
    fromIndex: Int = 0,
    toIndex: Int = size
): Int {
    var low = fromIndex
    var high = toIndex - 1

    while (low <= high) {
        val mid = (low + high).ushr(1)
        val midVal = get(mid)
        val cmp = midVal.compareTo(element)
        /** test for equality, then compare */
        when {
            cmp < 0 -> low = mid + 1
            cmp > 0 -> high = mid - 1
            else -> return mid // key found
        }
    }
    return -(low + 1)  // key not found.
}
