package borg.trikeshed.lib

//a boilerplate file for the kotlin compiler to generate the kotlin trikeshed commons lib

//binary search
fun <T:Comparable<T>> Series<T>.binarySearch(element: T, low :Int= 0, high :Int= size - 1): Int =binarySearch(element, naturalOrder(),low,high)

fun <T> Series<T>.binarySearch(element: T, c: Comparator<T>, low_ :Int= 0,  high_:Int = size - 1): Int{
    var low = low_
    var high = high_

    while (low <= high) {
        val mid = (low + high).ushr(1)
        val midVal = get(mid)
        val cmp = c.compare(midVal, element)
        when {
            cmp < 0 -> low = mid + 1
            cmp > 0 -> high = mid - 1
            else -> return mid // key found
        }
    }
    return -(low + 1)  // key not found.
}


//signed, unsigned primitives all need boilerplate here
fun Series<Byte>.binarySearch(element: Byte, low_ :Int= 0, high_:Int = size - 1): Int{
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

fun Series<Short>.binarySearch(element: Short, low_ :Int= 0, high_:Int = size - 1): Int{
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

fun Series<Int>.binarySearch(element: Int, low_ :Int= 0, high_:Int = size - 1): Int{
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

fun Series<Long>.binarySearch(element: Long, low_ :Int= 0, high_:Int = size - 1): Int{
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

fun Series<Float>.binarySearch(element: Float, low_ :Int= 0, high_:Int = size - 1): Int{
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

fun Series<Double>.binarySearch(element: Double, low_ :Int= 0, high_:Int = size - 1): Int{
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

fun Series<Char>.binarySearch(element: Char, low_ :Int= 0, high_:Int = size - 1): Int{
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
fun Series<UByte>.binarySearch(element: UByte, low_ :Int= 0, high_:Int = size - 1): Int{
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

fun Series<UShort>.binarySearch(element: UShort, low_ :Int= 0, high_:Int = size - 1): Int{
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

fun Series<UInt>.binarySearch(element: UInt, low_ :Int= 0, high_:Int = size - 1): Int{
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

fun Series<ULong>.binarySearch(element: ULong, low_ :Int= 0, high_:Int = size - 1): Int{
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