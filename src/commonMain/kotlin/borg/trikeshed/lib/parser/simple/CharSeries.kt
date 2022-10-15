package borg.trikeshed.lib.parser.simple

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.drop
import borg.trikeshed.lib.size
import borg.trikeshed.lib.take

/**
 * char based spiritual successor to ByteBuffer for parsing
 */
class CharSeries(buf: Series<Char>) : Series<Char> by buf {
    var position = 0
    var limit = size
    var mark = -1
    val slice get() = CharSeries(drop(position)) //not hashed, ever
    val get: Char
        get() {
            require(position < limit); return b(position++)
        }

    val hasNext get() = position < limit

    val mk = apply {
        mark = position
    }

    //reset
    val res = apply {
        position = mark
    }

    //flip
    val fl = apply {
        limit = position; position = 0
    }

    //rewind
    val rew = apply {
        position = 0
    }

    //clear
    val clr = apply {
        position = 0; limit = size;mark = -1
    }

    //position
    fun pos(p: Int) = apply {
        position = p
    }

    fun clone() = CharSeries(take(size))


    /** a hash of contents only. not position, limit, mark */
    val cacheCode: Int
        get() {
            var h = 1
            for (i in position until limit) {
                h = 31 * h + b(i).hashCode()
            }
            return h
        }

    override fun equals(other: Any?): Boolean {
        when {
            this === other -> return true
            other !is CharSeries -> return false
            position != other.position -> return false
            limit != other.limit -> return false
            mark != other.mark -> return false
            size != other.size -> return false
            else -> {
                for (i in 0 until size) {
                    if (b(i) != other.b(i)) return false
                }
                return true
            }
        }
    }

    /** idempotent, a cache can contain this hash and safely deduce the result from previous inserts */
    override fun hashCode(): Int {
        var result = position
        result = 31 * result + limit
        result = 31 * result + mark
        result = 31 * result + size
        //include cachecode
        result = 31 * result + cacheCode
        return result
    }

}