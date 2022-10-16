@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.lib.parser.simple

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.TypeMemento
import borg.trikeshed.lib.*
import borg.trikeshed.lib.collections.Stack
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.CoroutineContext.Key

typealias   CharParser = ConditionalUnary<CharSeries>
// FSM Charseries functors
//the token parser which is a finite state machine which parses the input stream into tokens, but also
//creates a list of token indexes for fast random access

infix fun CharParser.invoke(other: CharParser): CharParser = this + other

// can the FSM stuff the current coroutineContext with a CoroutineContext.Element that captures the current
// state and the next state and the input and the current token? and then the vetoable can
// just read it from the coroutineContext? yes, that would work

/** marker interface */
interface IAnyOf {
    val ops: Array<out CharParser>
}

/** marker interface */
interface IAllOf {
    val ops: Array<out CharParser>
}

@Deprecated("this is just a placeholder for collapsing a run of IAnyOf", replaceWith = ReplaceWith("concAnyOf"))
open class anyOf(override vararg val ops: CharParser) : CharParser by { s ->
    ops.firstNotNullOfOrNull { it(s) }
}, IAnyOf

@Deprecated("this is just a placeholder for collapsing a run of IAllOf", replaceWith = ReplaceWith("concAllOf"))
class allOf(override vararg val ops: CharParser) : IAllOf, CharParser by { s ->
    ops.reduce { acc, conditionalUnary -> { s -> acc(s)?.let { conditionalUnary(it) } } }(s)
}

class concAnyOf(override vararg var ops: CharParser) : IAnyOf, CharParser by { series ->
    runBlocking {
        val channel = Channel<Pair<CoroutineScope, CharSeries>>()
        supervisorScope { //we use this and 2 launches, instead of flow with concurrent merge. cancellation is less clear with flow
            launch {
                for ((index, op) in ops.withIndex()) {
                    //some ops are enums, take thier name, else use AnyOf$index
                    val name = (op as? Enum<*>)?.name ?: op::class.simpleName ?: "AnyOf$index"
                    val storedOp = store_(op, name)
                    launch {
                        val result = storedOp(series.clone())
                        if (result != null) {
                            val element = this to result
                            channel.send(element)
                        } else cancel()
                    }.join()
                }
            }
        }
        val receive = channel.receive()
        val (job, res) = receive
        currentCoroutineContext() + job.coroutineContext[ParseEnv.key]!!
        res
    }
}

class concAllOf(override vararg var ops: CharParser) : IAllOf, CharParser by { series ->
    runBlocking {
        val channel = Channel<Pair<CoroutineScope, CharSeries>>()
        supervisorScope { //we use this and 2 joins, instead of flow with concurrent merge. cancellation is less clear with flow
            launch {
                val series = series.clone()
                for ((index, op) in ops.withIndex()) {
                    //some ops are enums, take thier name, else use AnyOf$index
                    val name = (op as? Enum<*>)?.name ?: op::class.simpleName ?: "AllOf$index"
                    val storedOp = store_(op, name)
                    supervisorScope {
                        val result = storedOp(series)
                        if (result != null) {
                            val element = this to result
                            channel.send(element)
                        } else cancel()
                    }
                }
            }
        }
        val receive = channel.receive()
        val (job, res) = receive
        currentCoroutineContext() + job.coroutineContext[ParseEnv.key]!!
        res
    }
}


class backtrack_(val op: CharParser) : CharParser by {
    it.mk.let { op(it) }?.let { it.res; it } ?: it
}

class char_(val c: Char) : CharParser by {
    if (it.hasNext && (it.get == c)) it else null
}


/**compiles a skip-search compare function*/
@SkipWs
class string_(val match: String) : CharParser by { cs: CharSeries ->
    val slice = cs.slice
    slice.limit= match.length
    var c=0
    while(slice.hasNext && slice.get == match[c++]);
    if(cs.hasNext)null else cs
}

class confix_(
    val prefix: CharParser,
    val op: CharParser,
    val suffix: CharParser
) : CharParser by {
    prefix(it)?.let { op(it) }?.let { suffix(it) }
}

class infix_(val op: CharParser, val infix: CharParser) :
    CharParser by {
        op(it)?.let { infix(it) }
    }

/** limit the buffer size. */
class lim(val op: CharParser, val n: Int) : CharParser by {

    val b = it.clone()
    op(b)?.let { if (b.pos <= n) it else null }
}

class log_(val op: CharParser, val msg: String) : CharParser by {
    println(msg)
    op(it)
}

class dbg_(val op: CharParser) : CharParser by {
    println("dbg: ${it.slice.take(10)}")
    op(it)
}

/** opt_ stands for optional.
 * if the op returns null, then the input is returned as is.
 *
 * does the FSM rollback on failure? yes, it does.  it is a backtrack_ op.
 *
 * does the FSM need a hint for which ops are backtracking?  yes, it does.
 *
 */
class opt_(val op: CharParser) : CharParser by { s: CharSeries -> op(s)?.let { s } ?: s }

class pos(val op: CharParser, val p: Int) : CharParser by {
    it.pos(p).let { op(it) }?.let { it.pos(p); it }
}

//not
class not_(val op: CharParser) : CharParser by {
    op(it)?.let { null } ?: it
}


class repeat_(val op: CharParser, val n: Int = -1) : CharParser by {
    //-1 means repeat until failure
    var i = 0
    var s = it

    while (i != n) {
        s = op(s) ?: break
        i++
    }
    s
}

/** mk_ marks the current position in the input stream. */
class mk_ : CharParser by {
    it.mk
    it
}


class chrange_(val start: Char, val end: Char) : CharParser by {
    if (it.hasNext && (it.get in start..end)) it else null
}


class chgroup_(
    s: String,//sort and distinct the chars first to make the search faster,
    val chars: Series<Char> = s.toCharArray().distinct().sorted().toSeries()
) : CharParser {
    override fun invoke(p1: CharSeries): CharSeries? {
        // see https://pvk.ca/Blog/2012/07/03/binary-search-star-eliminates-star-branch-mispredictions/

        if (p1.hasNext) {
            val c = p1.get
            val i = chars.binarySearch(c)
            if (i >= 0) return p1
        }
        return null
    }

     companion object {
        //factory method for idempotent chgroup ops
        val cache = mutableMapOf<String, chgroup_>()
        fun of(s: String) = cache.getOrPut(s) { chgroup_(s) }
        val digit = of("0123456789")
        val hexdigit = of("0123456789abcdefABCDEF")
        val letter = of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
        val lower = of("abcdefghijklmnopqrstuvwxyz")
        val upper = of("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        val whitespace = of(" \t\n\r")
        val symbol = of("!@#\$%^&*()_+-=[]{}|;':\",./<>?")
    }
}


@Target(AnnotationTarget.CLASS)
/** this means that the parser does not need to be saved in the FSM. */
annotation class NoSave


/**fsm does trim the input tokens of this parser scope. */
@Target(AnnotationTarget.CLASS)
annotation class SkipWs


/**fsm does not trim the input tokens of this parser scope. */
@Target(AnnotationTarget.CLASS)
annotation class NoSkipWs // fsm does not trim the input of this parser

//nosave passthru parser
@NoSave
class nosave_(val op: CharParser) : CharParser by op

//skipws passthru parser
@SkipWs
class skipws_(val op: CharParser) : CharParser by op

//noskipws passthru parser
@NoSkipWs
class noskipws_(val op: CharParser) : CharParser by op

/** passthru emits a warn on null  */
class null_warn : CharParser by {
    it.let { it }
}

/** passthru throws on null  */
class null_throw(val throws: Throwable = TODO("because reasons")) : CharParser by {
    it.let { it }
}

/** passthru fires an Delegates.observer on invoke results */
class track_(val op: CharParser, val observer: (CharSeries?) -> Unit) :
    CharParser by {
        it.let { op(it).also { observer(it) } } ?: run {
            observer(null)
            null
        }
    }

infix fun IAnyOf.or(b: IAnyOf): IAnyOf =
    concAnyOf(*ops as Array<(CharSeries) -> CharSeries?> + b.ops as Array<(CharSeries) -> CharSeries?>)

infix fun IAllOf.and(b: IAllOf): IAllOf =
    concAllOf(*ops as Array<(CharSeries) -> CharSeries?> + b.ops as Array<(CharSeries) -> CharSeries?>)

infix fun CharParser.or(b: CharParser) = concAnyOf(this, b)
infix fun CharParser.and(b: CharParser) = concAllOf(this, b)


/** not_ */
operator fun CharParser.not() = not_(this)

/**repeat minimum/max */
operator fun CharParser.get(n: IntRange) =
    repeat_(this, n.last) and opt_(this)[n.first - n.last]

/**
 * repeat, -1 means repeat until failure
 */
operator fun CharParser.get(n: Int) = repeat_(this, n)

/** optional  -- synonyms: a[b] , a*b  */
operator fun CharParser.get(op: CharParser) = this and opt_(op)

/** stores state with name */
operator fun CharParser.get(name: String) = store_(this, name)

/** stores state with name and type parser*/
operator fun CharParser.get(meta: Join<String, TypeMemento>) = store_(this, meta.a, meta.b)

/** optional, anyOf */
operator fun CharParser.div(op: CharParser) = this or op
operator fun CharParser.div(c: Char): CharParser = this / char_(c)
operator fun CharParser.div(s: String): CharParser = this / string_(s)

/**and, allOf*/
operator fun CharParser.plus(op: CharParser) = this and op
operator fun CharParser.plus(c: Char): CharParser = this + char_(c)
operator fun CharParser.plus(s: String): CharParser = this + string_(s)


/** zero or more this */
operator fun CharParser.times(op: CharParser) = this[-1] + (op)
operator fun CharParser.times(c: Char): CharParser = this * char_(c)
operator fun CharParser.times(s: String): CharParser = this * string_(s)

/** one but not both */
operator fun CharParser.rem(op: CharParser) = this and not_(op) or op and not_(this)
operator fun CharParser.rem(c: Char): CharParser = this % char_(c)
operator fun CharParser.rem(s: String): CharParser = this % string_(s)

/**this and not*/
operator fun CharParser.minus(op: CharParser) = this and not_(op) //
operator fun CharParser.dec() = opt_(this)
fun String.dec() = opt_(+this)
fun Char.dec() = opt_(+this)
operator fun CharParser.minus(c: Char): CharParser = this - c
operator fun CharParser.minus(s: String): CharParser = this - s

object `^` : CharParser by { it -> skipws_({ s -> s })(it) }

data class FSMState(
    val name: String,
    val parser: CharParser,
    val typ: TypeMemento = IOMemento.IoString,
)

/**this is the FSM State referenced in op push.  This is created in a parser's runBlocking or children coroutine context, obviously.  read up on CoroutineContext.Element */
data class ParseEnv(
    /** intended to label a single FSM state */
    var statename: String = "start",

    /** a stack of FSM states */
    val opstack: Stack<FSMState> = Stack()
) : CoroutineContext.Element {


    companion object { //static
        /** intended to label a single FSM state */
        var statename: String = "start"

        //system-wide map of  TypeMemento to (String)->Any?
        val conversions = HashMap<TypeMemento, (String) -> Any?>()

        //coroutineElement key
        val key: Key<ParseEnv> = object : Key<ParseEnv> {}


    }

    override val key: Key<*>
        get() = Companion.key

    fun clone(): CoroutineContext.Element { //clone
        return ParseEnv(statename, opstack.clone())
    }

}


/** store_ stores the result of the op in the fsm */
class store_(
    val op: CharParser,
    val name: String,
    val metaType: TypeMemento = IOMemento.IoString
) : CharParser {
    override fun invoke(p1: CharSeries): CharSeries? = runBlocking {
        val ret = op(p1)
        if (ret != null) {
            val env = currentCoroutineContext()[ParseEnv.key]
            if (env != null) {
                env.statename = name
                env.opstack.push(FSMState(name, op, metaType))
            }
        }
        ret
    }
}

/** push_ pushes the op onto the fsm */
class push_(
    val op: CharParser,
    val name: String,
    val metaType: TypeMemento = IOMemento.IoString
) : CharParser {
    override fun invoke(p1: CharSeries): CharSeries? = runBlocking {
        val ret = op(p1)
        if (ret != null) {
            val env = currentCoroutineContext()[ParseEnv.key]
            if (env != null) {
                env.statename = name
                env.opstack.push(FSMState(name, op, metaType))
            }
        }
        ret
    }
}

/** pop_ pops the op from the fsm */
class pop_(
    val op: CharParser,
    val name: String,
    val metaType: TypeMemento = IOMemento.IoString
) : CharParser {
    override fun invoke(p1: CharSeries): CharSeries? = runBlocking {
        val ret = op(p1)
        if (ret != null) {
            val env = currentCoroutineContext()[ParseEnv.key]
            if (env != null) {
                env.statename = name
                env.opstack.pop()
            }
        }
        ret
    }
}

/** peek_ peeks the op from the fsm */
class peek_(
    val op: CharParser,
    val name: String,
    val metaType: TypeMemento = IOMemento.IoString
) : CharParser {
    override fun invoke(p1: CharSeries): CharSeries? = runBlocking {
        val ret = op(p1)
        if (ret != null) {
            val env = currentCoroutineContext()[ParseEnv.key]
            if (env != null) {
                env.statename = name
                env.opstack.peek()
            }
        }
        ret
    }
}

/** backtrack quietly on fail as success */
class bof_(val op: CharParser) : CharParser {
    override fun invoke(p1: CharSeries): CharSeries = runBlocking {
        val clone = p1.clone()
        op(p1) ?: clone
    }
}


// the nars reasoner logic:
// 1.  parse the input into a task
// 2.  store the task in the memory
// 3.  derive new tasks from the stored tasks
// 4.  store the new tasks in the memory
// 5.  repeat 3 and 4 until no new tasks are derived
// 6.  output the tasks in the memory
// 7.  repeat 1-6

// the nars parser logic:
// 1.  parse the input into a task
// 2.  store the task in the memory
// 3.  repeat 1-2 until no new tasks are derived
// 4.  output the tasks in the memory
// 5.  repeat 1-4 until no new tasks are derived


typealias `-_` = CharParser

infix operator fun Char.get(s: CharParser): CharParser = (+this)[s]
infix operator fun Char.invoke(s: Char): CharParser = (+this) + s
infix operator fun Char.invoke(s: CharParser): CharParser = (+this) + s
infix operator fun Char.invoke(s: String): CharParser = (+this) + s
infix operator fun Char.minus(s: CharParser): CharParser = (+this) - s
infix operator fun Char.plus(s: CharParser): CharParser = (+this) + s
infix operator fun Char.plus(s: String): CharParser = (+this) + s
infix operator fun Char.times(s: CharParser): CharParser = (+this) * s
infix operator fun String.div(s: CharParser): CharParser = (+this) / s
infix operator fun String.get(s: CharParser): CharParser = (+this)[s]
infix operator fun String.invoke(s: Char): CharParser = (+this) + s
infix operator fun String.invoke(s: CharParser): CharParser = (+this) + s
infix operator fun String.invoke(s: String): CharParser = (+this) + s
infix operator fun String.minus(s: CharParser): CharParser = (+this) - s
infix operator fun String.plus(s: CharParser): CharParser = (+this) + s
infix operator fun String.times(s: CharParser): CharParser = (+this) * s
operator fun Char.unaryPlus(): CharParser = `^` + this
operator fun String.unaryPlus(): CharParser = `^` + this
