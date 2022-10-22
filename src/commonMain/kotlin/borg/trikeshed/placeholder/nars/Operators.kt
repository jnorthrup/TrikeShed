@file:Suppress("ControlFlowWithEmptyBody")

package borg.trikeshed.placeholder.nars

import borg.trikeshed.lib.*
import borg.trikeshed.lib.parser.simple.CharSeries
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

typealias ConditionalUnary<T> = (T) -> T?
typealias ParseFunction = ConditionalUnary<CharSeries>
typealias Parser = (ParseFunction, CharSeries) -> ParseNode?


/**
 * this is assumed to be a terminal, more or less.  It will not recurse.
 */
typealias `^` = ParseFunction
/**
 * IAllOf requires a first class type in order to dispatch by type instead of carry around a runtime memento of the type.
 */
typealias `^^` = IAllOf
/**
 * IAnyOf requires a first class type in order to dispatch by type instead of carry around a runtime memento of the type.
 */
typealias `^-` = IOneOf

//1. unaryPlus (promotion to parse rule) from Char, String, /*Regex,*/ Iterable<Char>,
operator fun Char.unaryPlus(): `^` = char_(this)
operator fun String.unaryPlus(): `^` = string_(this)

//operator fun Regex.unaryPlus(): `^^` = pattern_(this)
operator fun Iterable<Char>.unaryPlus(): `^` = group_(this)

//1. a `+` b (and)
operator fun `^`.plus(b: `^`)  = allOf_(this, b)
operator fun `^`.plus(b: Char) = oneOf_(this, +b)
operator fun `^`.plus(b: String)  = allOf_(this, +b)

//1. a `/` b (a or b)
operator fun `^`.div(b: `^`): `^-` = oneOf_(this, b)


operator fun `^`.div(b: Char): `^-` = oneOf_(this, +b)
operator fun `^`.div(b: String): `^-` = oneOf_(this, +b)

//1. a[-1] repeat until counter ==specified value (-n is infinite)
operator fun `^`.get(n: Int): `^` = repeat_(this, n)

//1. a[2..5] repeat at least 2, up to 5
operator fun `^`.get(range: IntRange): `^^` = this[range.first] + opt_(this)[range.last - range.first]

//1. a `*` b (zero or more a, one b)
operator fun `^`.times(b: `^`): `^^` = this[-1] + b
operator fun `^`.times(b: Char): `^^` = (this)[-1] + +b
operator fun `^`.times(b: String): `^^` = (this)[-1] + +b

//1. a[b] (one a ,optional b)
operator fun `^`.get(b: `^`): `^^` = (this) + opt_(b)
operator fun `^`.get(b: Char): `^^` = (this) + opt_(+b)

//1. `!` (not)
operator fun `^`.not(): `^` = not_(this)


//1. a["named"] (named value)
operator fun `^`.get(name: String): `^` = named_(this, name)

//1. 'a'..'z' (range of characters)
operator fun Char.rangeTo(b: Char): `^` = range_(this, b)
class range_(val a: Char, val b: Char) : `^` by { cs -> cs.takeIf { it.get in a..b } }

//1. a 'b' (character) a+ (+b)
infix operator fun `^`.invoke(a: Char): `^^` = this + (+a)

//1. a "abc" (string)
infix operator fun `^`.invoke(a: String): `^^` = this + (+a)

//unicode upside down question mark: \u00BF (¿)
infix fun `^`.`¿`(a: `^`): `^^` = opt_(this) + (a)
infix fun `^`.`¿`(a: Char): `^^` = opt_(this) + (+a)
infix fun `^`.`¿`(a: String): `^^` = opt_(this) + (+a)

interface IAllOf : `^` {
    val rules: Sequence<`^`>
}

interface IOneOf  : `^`{
    val rules: Sequence<`^`>
}


operator fun `IAllOf`.plus(b: IAllOf): `^^` = allOf_(this.rules + (b.rules))

operator fun `IOneOf`.div(b: IOneOf): `^-` = oneOf_(this.rules + b.rules)


fun char_(c: Char): `^` = { cs: CharSeries ->
    cs.takeIf { cs.get == c }
}


interface IKeepWS
interface ISkipWS
interface IBackTrack
interface IForwardOnly
interface INamed {
    val name: String
}

class named_(val rule: `^`, override val name: String) : INamed, `^` by rule

//object skipWs_ : IBackTrack, `^^` by +_l[' ', '\r', '\n', '\t']

//class backtrack_(r: `^^`) : IBackTrack, `^^` by { cs: CharSeries ->
//    //call r with a clone of cs and return the clone if it fails
//    val clone = cs.clone()
//    r(clone) ?: clone
//}

class not_(r: `^`) : IBackTrack, `^` by { cs: CharSeries -> cs.clone().takeIf { r(cs) == null } }


/** see also `¿` */
class opt_(rule: `^`) : IBackTrack, `^` by { cs: CharSeries -> rule(cs) }
class keepWs_(rule: `^`) : IKeepWS, `^` by { cs: CharSeries -> rule(cs) }
class forwardOnly_(rule: `^`) : IForwardOnly, `^` by { cs: CharSeries -> rule(cs) }
class repeat_(rule: `^`, count: Int = -1) : `^` by { cs: CharSeries ->
    var c = 0
    var result: CharSeries? = cs
    var pos = cs.pos
    do {
        result = rule(result!!.also { pos = it.pos })

    } while (result != null && (c++ != count))
    if (result == null) cs.pos(pos)
    else result
}  //this functor wants as post-flip


class string_(val str: String) : IBackTrack, `^` by { cs: CharSeries ->
    var c = 0
    @Suppress("ControlFlowWithEmptyBody")
    while (cs.hasNext && cs.get == str[c++]);
    cs.takeIf { c == str.length }

}

class chgroup_(
    s: String,//sort and distinct the chars first to make the search faster,
    private val chars: Series<Char> = s.toCharArray().distinct().sorted().toSeries()
) : `^` {
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
        fun of(s: String) = cache.getOrPut(s) { chgroup_(s.debug { s -> logDebug { "chgrp: ($s) " } }) }
        val digit = of("0123456789")
        val hexdigit = of("0123456789abcdefABCDEF")
        val letter = of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
        val lower = of("abcdefghijklmnopqrstuvwxyz")
        val upper = of("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        val whitespace = of(" \t\n\r")
        val symbol = of("!@#\$%^&*()_+-=[]{}|;':\",./<>?")
    }
}

inline fun group_(grp: Iterable<Char>): `^` = chgroup_.of(grp.joinToString(""))

open class ParseNode(
    var name: String = "node-${counter++}",
    var value: CharSeries? = null,
    var children: MutableList<ParseNode> = ArrayList()
) {

    constructor(other: ParseNode) : this(other.name, other.value, other.children.map { ParseNode(it) }.toMutableList())

    fun clone() = ParseNode(this)

    companion object {
        var counter = 0
    }
}


class allOf_(override val rules: Sequence<`^`>) : IAllOf, IBackTrack, `^` {
    constructor(vararg rulesIn: `^`) : this(rulesIn.asSequence())

    override fun invoke(p1: CharSeries): CharSeries = TODO()
}

class oneOf_(
    override val rules: Sequence<`^`>
) : IOneOf, `^` {
    constructor(vararg rulesIn: `^`) : this(rulesIn.asSequence())

    override fun invoke(p1: CharSeries): CharSeries = TODO()
}

abstract class RecursiveContextParser : CoroutineContext.Element, Parser {
    companion object {
        val key: CoroutineContext.Key<*> = object : CoroutineContext.Key<CoroutineContext.Element> {}
    }


    override val key: CoroutineContext.Key<*> = RecursiveContextParser.key

    //boools for annotation toggles incoming
    var skipWs = false //opposite by keepWs
    var backTrack = false  //opposite by  forwardOnly
    var named = false
    open var name = ""
    fun prepareFor(rule: `^`) =
        when (rule) {
            is IKeepWS -> skipWs = false
            is ISkipWS -> skipWs = true
            is IBackTrack -> backTrack = true
            is IForwardOnly -> backTrack = false
            is INamed -> {
                named = true
                name = getName(rule)
            }

            else -> {}
        }

    fun getName(needsName: `^`): String {
        //check if INamed, Enum<*>, KClass<*>, String, or Char

        //how will we get a KClass in kotlin common using kotlin.reflect?

        return when {
            needsName is INamed -> needsName.name
            needsName is Enum<*> -> needsName.name
            else -> needsName::class.simpleName!!
        }
    }


    abstract fun clone(): CoroutineContext


    val publisherLock: Mutex = Mutex()
    abstract val parent: RecursiveContextParser?
}

/**  RecursiveContextParser is a CoroutineContext.Element that recursively runs parsers which have children parsers,
and returns a ParseNode that bubbles up.  prepareFor is called on each rule before it is run, and informs inline
lexeme and positional tracking decisions which hapen before and after the rule is run.

the marker interfaces we are interested in are:  IKeepWS, ISkipWS, IBackTrack, IForwardOnly, INamed which will be evaluated in prepareFor

the processing of IOneOf will create a RecursiveContextParser for each rule in the sequence inside of supervisorContext, and run them in parallel
with a mutex lock on the current publisherLock for first completion.

the processing of IAllOf will create a RecursiveContextParser for each rule in the sequence, and run them in
sequence so that the flags are set correctly for each rule.  the last rule will be the one that is returned to the
parent context.  the parent context will then be returned to the parent context, and so on.
 */
class RecursiveContextParserImpl(override val parent: RecursiveContextParser? = null) : RecursiveContextParser() { //, CoroutineScope

    override fun invoke(rule: ParseFunction, cs: CharSeries): ParseNode = runBlocking {
        lateinit var result: ParseNode
        prepareFor(rule)
        val csClone = if (backTrack) cs.clone() else cs

        if (skipWs) while (csClone.hasNext && csClone.get.isWhitespace()); //skip whitespace

        when (rule) {
            /*
                        the processing of IOneOf will create a RecursiveContextParser for each rule in the sequence
                        inside supervisorContext, and run them in parallel
                        with a mutex lock on the current publisherLock for first completion.
                */
            is IOneOf -> runBlocking {
                supervisorScope { //run in parallel
                    rule.rules.forEach { r ->
                        for (childRule in rule.rules) {
                            val job = launch {
                                val childParser = RecursiveContextParserImpl(this@RecursiveContextParserImpl)
                                childParser(childRule, csClone).let {
                                    publisherLock.withLock {
                                        result = it
                                        this@RecursiveContextParserImpl.cancelChildren()
                                    }
                                }
                            }
                        }
                        joinAll()
                        publisherLock.unlock()
                    }
                }
            }
            /*
                    the processing of IAllOf will create a RecursiveContextParser for each rule in the sequence, and run them in
                    sequence so that the flags are set correctly for each rule.  the last rule will be the one that is returned to the
                    parent context.  the parent context will then be returned to the parent context, and so on.
            */
            is IAllOf -> runBlocking {
                rule.rules.forEach { r ->
                    val childParser = RecursiveContextParserImpl(this@RecursiveContextParserImpl)
                    val childResult = childParser(r, csClone)
                    if (childResult != null) {
                        result = childResult
                    } else {
                        cancel()
                    }
                }
            }

            else -> {
                result = (rule(csClone)?.let {
                    ParseNode(
                        name = if (named) name else rule::class.simpleName!!,
                        value = it
                    )
                } ?: cancel()) as ParseNode
            }
        }
        result
    }

    override fun clone(): CoroutineContext = RecursiveContextParserImpl(this)
}

