package borg.trikeshed.placeholder.nars

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.binarySearch
import borg.trikeshed.lib.collections._l
import borg.trikeshed.lib.collections.s_
import borg.trikeshed.lib.parser.simple.CharSeries
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.`▶`
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlin.coroutines.CoroutineContext


//1. unaryPlus (promotion to parse rule) from Char, String, /*Regex,*/ Iterable<Char>,
operator fun Char.unaryPlus(): `^^` = char_(this)
operator fun String.unaryPlus(): `^^` = string_(this)

//operator fun Regex.unaryPlus(): `^^` = pattern_(this)
operator fun Iterable<Char>.unaryPlus(): `^^` = group_(this)

//1. a `+` b (and)
operator fun `^^`.plus(b: `^^`): `^^` = allOf_(this, b)

operator fun `^^`.plus(b: Char): `^^` = oneOf_(this, +b)
operator fun `^^`.plus(b: String): `^^` = allOf_(this, +b)

//1. a `/` b (a or b)
operator fun `^^`.div(b: `^^`): `^^` = oneOf_(this, b)


operator fun `^^`.div(b: Char): `^^` = oneOf_(this, +b)
operator fun `^^`.div(b: String): `^^` = oneOf_(this, +b)

//1. a[-1] repeat until counter ==specified value (-n is infinite)
operator fun `^^`.get(n: Int): `^^` = repeat_(this, n)

//1. a[2..5] repeat at least 2, up to 5
operator fun `^^`.get(range: IntRange): `^^` = this[range.first] + opt_(this)[range.last - range.first]

//1. a `*` b (zero or more a, one b)
operator fun `^^`.times(b: `^^`): `^^` = this[-1] + b
operator fun `^^`.times(b: Char): `^^` = (this)[-1] + +b
operator fun `^^`.times(b: String): `^^` = (this)[-1] + +b

//1. a[b] (one a ,optional b)
operator fun `^^`.get(b: `^^`): `^^` = (this) + b[0..1]
operator fun `^^`.get(b: Char): `^^` = (this) + (+b)[0..1]

//1. `!` (not)
operator fun `^^`.not(): `^^` = not_(this)


//1. a["named"] (named value)
operator fun `^^`.get(name: String): `^^` = named_(this, name)

//1. 'a'..'z' (range of characters)
operator fun Char.rangeTo(b: Char): `^^` = range_(this, b)
class range_(val a: Char, val b: Char) : `^^` by { cs -> cs.takeIf { it.get in a..b } }

//1. a 'b' (character) a+ (+b)
infix operator fun `^^`.invoke(a: Char): `^^` = this + (+a)

//1. a "abc" (string)
infix operator fun `^^`.invoke(a: String): `^^` = this + (+a)

interface IAllOf {
    val rules: Sequence<`^^`>
}

class allOf_(override val rules: Sequence<`^^`>) : IAllOf, IBackTrack, `^^` {
    constructor(vararg rulesIn: `^^`) : this(rulesIn.asSequence())

    override fun invoke(p1: CharSeries): CharSeries? {
        var cs: CharSeries = p1
        for (rule in rules) {
            cs = rule(cs) ?: return null
        }
        return cs
    }
}

interface IOneOf {
    val rules: Sequence<`^^`>
}

class oneOf_(
    override val rules: Sequence<`^^`>
) : IOneOf, `^^` {
    constructor(vararg rulesIn: `^^`) : this(rulesIn.asSequence())

    override fun invoke(p1: CharSeries): CharSeries? {
        return rules.map { it(p1) }.firstOrNull { it != null }
    }
}

operator fun `IAllOf`.plus(b: IAllOf): `^^` = allOf_(this.rules + (b.rules))

operator fun `IOneOf`.div(b: IOneOf): `^^` = oneOf_(this.rules + b.rules )


fun char_(c: Char): `^^` = { cs: CharSeries ->
    cs.takeIf { cs.get == c }
}


interface IKeepWS
interface ISkipWS
interface IBackTrack
interface IForwardOnly
interface INamed {
    val name: String
}

class named_(val rule: `^^`, override val name: String) : INamed, `^^` by rule


object skipWs_ : IBackTrack, `^^` by +_l[' ', '\r', '\n', '\t']

class backtrack_(r: `^^`) : IBackTrack, `^^` by { cs: CharSeries ->
//call r with a clone of cs and return the clone if it fails
    val clone = cs.clone()
    r(clone)

}

class not_(r: `^^`) : IBackTrack, `^^` by { cs: CharSeries -> cs.clone().takeIf { r(cs) == null } }


open class ParseNode(
    var name: String="node-${counter++}",
    var value: CharSeries?=null,
    var children: MutableList<ParseNode> = ArrayList()
)   {

    constructor(other: ParseNode) : this(other.name, other.value, other.children.map { ParseNode(it) }.toMutableList())

    fun clone() = ParseNode(this)
    companion object {
        var counter = 0
    }
}


val `^^`.name
    get() =     /*check for INamed,  Enum, and KClass names first*/ (this as? INamed)?.name ?: (this as? Enum<*>)?.name
    ?: this::class.simpleName ?: toString()

/**
a single recursive FSM tree builder for all rules using runBlocking to open a new CoroutineContext and installing
a CoroutineElement to track the current rule being parsed and the current position in the input CharSeries.
The CoroutineElement is used to implement backtracking and forward-only parsing. The CoroutineElement is also
used to implement named values. The CoroutineElement is also used to implement the SkipWS and KeepWS annotations.
the ConditionalUnary<CharSeries> is the parse rule. The CharSeries is the input to be parsed. The ParseNode is
the CoroutineContext element itself when complete.  during the parser run, the CoroutineContext refers to a parental Stack of ParseNodes when locally null,
otherwise it will fill the local stack with the current ParseNode and its children to be merged into the parental stack when the rule completes.
 */


class FSM(var root: Series<`^^`>, var parseNode: ParseNode = ParseNode()) : CoroutineContext.Element, `^^` {
    companion object {

        val key: CoroutineContext.Key<*> = object : CoroutineContext.Key<CoroutineContext.Element> {}
    }

    override val key: CoroutineContext.Key<*> = FSM.key

    //boools for annotation toggles incoming
    var skipWs = false //opposite by keepWs
    var backTrack = false  //opposite by  forwardOnly
    var named = false
    var name = ""

    fun getName(needsName: `^^`): String {
        //check if INamed, Enum<*>, KClass<*>, String, or Char

        //how will we get a KClass in kotlin common using kotlin.reflect?

        return when {
            needsName is INamed -> needsName.name
            needsName is Enum<*> -> needsName.name
            else -> needsName::class.simpleName!!

        }

    }

    // * when rule is IKeepWS, the skipWs boolean is set to false
    // * when rule is ISkipWS, the skipWs boolean is set to true
    // * when rule is IBackTrack, the backTrack boolean is set to true
    // * when rule is IForwardOnly, the backTrack boolean is set to false
    // * when rule is INamed, the named boolean is set to true and the name is set to the name of the rule
    fun decorate(rule: `^^`): `^^` {
        var r: `^^` = rule
        if (skipWs) r = skipWs_ * r
        if (backTrack) r = backtrack_(r)
        return r
    }

    fun prepareFor(rule: `^^`) =
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


    //clone method
    fun clone(): FSM = FSM(root, parseNode.clone()).also {
        it.skipWs = skipWs
        it.backTrack = backTrack
        it.named = named
        it.name = name


    }


    override operator fun invoke(cs: CharSeries): CharSeries {
        val theFsm = this
        runBlocking(this) {
            for ((ix, rule) in root.`▶`.withIndex()) supervisorScope{
                //update name with +"$ix"
                name += ":$ix"
                prepareFor(rule)


                //if rule is IAllOf, run the sequence in a supervisor FSM job that is a clone of this FSM
                //if rule is IOneOf, run each element in a clone of the current FSM and the first to complete wins
                //if rule is neither, run the root in the current context
                when (rule) {
                    is IOneOf -> {
                        channelFlow<FSM> {
                            launch {
                                for (r in rule.rules) supervisorScope{
                                    launch {
                                        val fsm = theFsm.clone()
                                        fsm.root = s_[r]
                                        fsm.parseNode.name = name
                                        val result = (decorate(fsm)(cs))?.let {
                                            send(fsm)
                                        } ?: cancel()
                                    }
                                }
                            }
                        }.firstOrNull()?.let {
                            //copy the fsm details into this fsm
                            skipWs = it.skipWs
                            backTrack = it.backTrack
                            named = it.named
                            name = it.name
                            parseNode = it.parseNode
                        } ?: cancel()

                    }

                    is IAllOf -> {
                        val fsm = theFsm.clone()
                        fsm.root = rule.rules.toSeries()
                        fsm.parseNode.name = name
                        (decorate(fsm)(cs))?.let {
                            //copy the fsm details into this fsm
                            skipWs = fsm.skipWs
                            backTrack = fsm.backTrack
                            named = fsm.named
                            name = fsm.name
                            parseNode = fsm.parseNode
                        } ?: cancel()
                    }

                    else -> {
                        val result = (decorate(rule)(cs))?.let {
                            //copy the fsm details into this fsm
                            skipWs = theFsm.skipWs
                            backTrack = theFsm.backTrack
                            named = theFsm.named
                            name = theFsm.name
                            parseNode = theFsm.parseNode
                        } ?: cancel()
                    }
                }
            }
        }
        return parseNode.value as CharSeries

    }
}

class opt_(rule: `^^`) : IBackTrack, `^^` by { cs: CharSeries -> rule(cs) }
class keepWs_(rule: `^^`) : IKeepWS, `^^` by { cs: CharSeries -> rule(cs) }
class forwardOnly_(rule: `^^`) : IForwardOnly, `^^` by { cs: CharSeries -> rule(cs) }
class repeat_(rule: `^^`, count: Int = -1) : `^^` by { cs: CharSeries ->
    var c = 0
    var result: CharSeries? = cs
    var pos = cs.pos
    do {
        result = rule(result!!.also { pos = it.pos })

    } while (result != null && (c++ != count))
    if (result == null) cs.pos(pos)
    else result
}  //this functor wants as post-flip


class string_(val str: String) : IBackTrack, `^^` by { cs: CharSeries ->
    var c = 0
    @Suppress("ControlFlowWithEmptyBody")
    while (cs.hasNext && cs.get == str[c++]);
    cs.takeIf { c == str.length }

}

class chgroup_(
    s: String,//sort and distinct the chars first to make the search faster,
    private val chars: Series<Char> = s.toCharArray().distinct().sorted().toSeries()
) : `^^` {
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

inline fun group_(grp: Iterable<Char>): `^^` = chgroup_.of(grp.joinToString(""))



