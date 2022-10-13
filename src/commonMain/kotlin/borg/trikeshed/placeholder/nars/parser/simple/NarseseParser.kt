@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.placeholder.nars.parser.simple

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.TypeMemento
import borg.trikeshed.lib.*
import borg.trikeshed.lib.collections.Stack
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.CoroutineContext.Key
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty


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

// FSM Charseries functors
//the token parser which is a finite state machine which parses the input stream into tokens, but also
//creates a list of token indexes for fast random access

typealias ConditionalUnary<T> = (T) -> T?
typealias CharParser = ConditionalUnary<CharSeries>

// can the FSM stuff the current coroutineContext with a CoroutineContext.Element that captures the current
// state and the next state and the input and the current token? and then the vetoable can
// just read it from the coroutineContext? yes, that would work

interface IAnyOf {
    val ops: Array<out CharParser>
}

interface IAllOf {
    val ops: Array<out CharParser>
}

open class anyOf(override vararg val ops: CharParser) : CharParser by { s -> ops.firstNotNullOfOrNull { it(s) } },
    IAnyOf

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
                    val name = (op as? Enum<*>)?.name ?: op::class.simpleName ?: "AnyOf$index"
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
class string_(val s: String) : CharParser {


    companion object {
        val leastFrequentCharIndex by lazy {
            val mostFreq = "ETAOINSHRDLU"
            val chars =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toSet() - mostFreq.toSet() //returns "BCFGJKMPQVWXYZ"
            val chars1 =
                mostFreq.toCharArray() + chars.toCharArray() //returns "ETAOINSHRDLUBCFGJKMPQVWXYZ"
            val s = chars1.reversedArray() //returns  "ZYXWVQPJKMGCFLDRSHNIOTSAOIE"
            s//returns
        }
    }

    val searchOrder = s.let { s ->
        //create a search order from leastFrequentCharIndex + (s.uppercase() -leastFrequentCharIndex )
        val SU = s.uppercase()

        val v1 = leastFrequentCharIndex.toTypedArray().toCharArray().toSet() + SU.toCharArray()
            .toSet()


        val union = v1.distinct()

        val prs = SU.mapIndexed { x, c ->
            union.indexOf(c)
            x to c
        }
        prs.sortedBy { it.second }.map { it.first }
    }

    override fun invoke(p1: CharSeries): CharSeries? {
        val slice = p1.slice
        if (slice.size < s.length) return null
        //compare in order of searchOrder
        for (i in searchOrder) {
            if (slice.b(i) != s[i]) return null
        }
        //if we get here, we have a match
        p1.pos(p1.position + s.length)
        return p1
    }


}
//    test first, last, mid chars

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
    op(b)?.let { if (b.position <= n) it else null }
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
 *
 */
class opt_(val op: CharParser) : CharParser by { s ->
    op(s)?.let { s } ?: s


}

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

class chgroup_(val s: String) : CharParser {
    //sort and distinct the chars first to make the search faster
    val chars = s.toCharArray().sorted().distinct()
    override fun invoke(p1: CharSeries): CharSeries? = runBlocking {
        //if more than 32 use binary search
        if (chars.size > 32) {
            if (p1.hasNext && (chars.binarySearch(p1.get) >= 0)) p1 else null
        } else {
            //if less than 32 use Int as bitset
            var bs = 0
            chars.forEach { bs = bs or (1 shl (it - 'a')) }
            if (p1.hasNext && ((bs and (1 shl (p1.get - 'a'))) != 0)) p1 else null
        }
    }

    //cache these for fast lookup
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

infix fun IAnyOf.or(b: anyOf): IAnyOf =
    concAnyOf(*ops as Array<(CharSeries) -> CharSeries?> + b.ops as Array<(CharSeries) -> CharSeries?>)

infix fun IAllOf.and(b: allOf): IAllOf =
    concAllOf(*ops as Array<(CharSeries) -> CharSeries?> + b.ops as Array<(CharSeries) -> CharSeries?>)

infix fun CharParser.or(b: CharParser) = anyOf(this, b)
infix fun CharParser.and(b: CharParser) = allOf(this, b)
operator fun CharParser.not() = not_(this)
operator fun CharParser.get(n: Int) = repeat_(this, n)

/**repeat minimum/max */
operator fun CharParser.get(n: IntRange) =
    repeat_(this, n.last) and opt_(this)[n.first - n.last]

/** optional*/
operator fun CharParser.get(op: CharParser) = this and opt_(op)

/** stores state with name */
operator fun CharParser.get(name: String) = store_(this, name)

/** stores state with name and type parser*/
operator fun CharParser.get(meta: Join<String, TypeMemento>) = store_(this, meta.a, meta.b)

/** alt optional */
operator fun CharParser.div(op: CharParser) = this or op
operator fun CharParser.plus(op: CharParser) = this and op

/** this and opt_ op*/
operator fun CharParser.times(op: CharParser) = this and opt_(op)
operator fun CharParser.minus(op: CharParser) = this and not_(op) //

/** one but not both */
operator fun CharParser.rem(op: CharParser) =
    this and not_(op) or op and not_(this)


//parser operator overloading  legend:
//   |operator | meaning | example |
//   |---------|---------|---------|
//   |   +     | and     | a + b   |
//   |   -     | not     | a - b   |
//   |   *     | and opt | a * b   |
//   |   /     | or      | a / b   |
//   |   %     | xor     | a % b   |
//   |   []    | repeat  | a[3]    |
//   |   []    | repeat  | a[3..5] |
//   |   []    | opt     | a[b]    |
//   |   []    | store   | a["name"]|
//   |   []    | store   | a["name":type]|
//   |   ()    | passthru| a(b)    |
//   |   ()    | passthru| a(b,c)  |
//   |   ()    | passthru| a(b,c,d)|


//wraps a ConditionalUnary ".invoke" operator with a "by Delegates.vetoable" property that can be vetoed by a vetoable function
open class VetoableUnaryConditional<T>(
    /**can be changed as needed*/
    private val f: ConditionalUnary<T>,
    /**can be changed as needed*/
    var vetoable: ReadWriteProperty<Any?, ConditionalUnary<T>>,
    /** intended to label a single FSM state */
    val statename: String
) : ConditionalUnary<T> by f {
    override fun invoke(p1: T): T? {

        val v: (T) -> T? = vetoable.getValue(this, object : KProperty<T?> {
            override val name: String
                get() = statename
        })
        return v(p1)
    }
}

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
        get() = ParseEnv.key

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


// the parser needs cold, lazy kotlin Sequence or TrikeShed Series to work with the forward and backward token
// definition and to avoid stack overflow

//the nars grammars, with annotations and meta data, are defined in the following data classes


//the Narsese BNF grammar:

//next we define the same grammar while removing the left recursion and left factoring
//the grammar is now LL(1) and can be parsed with a simple recursive descent parser

// <symbol> is a nonterminal (variable) and the __expression__ consists of one or more sequences of either terminal or nonterminal symbols;
// ::= means that the symbol on the left must be replaced with the expression on the right.
//  more sequences [of symbols] are separated by the vertical bar "|", indicating a choice, the whole being a possible substitution for the symbol on the left.
// '#' as a token prefix indicates regex pattern
// { } around a construction indicates a repeating group
//--------------------------------------------------------------------------------------------------------
//
//               task ::= [budget] sentence                       (* task to be processed *)
//
//         sentence ::= statement"." [tense] [truth]            (* judgement to be absorbed into beliefs *)
//                    / statement"?" [tense] [truth]            (* question on thuth-value to be answered *)
//                    / statement"!" [desire]                   (* goal to be realized by operations *)
//                    / statement"@" [desire]                   (* question on desire-value to be answered *)
//
//
//           copula ::= "-->"                                   (* inheritance *)
//                    / "--]"                                   (* property *)
//                    / "<->"                                   (* similarity *)
//                    / "</>"                                   (* predictive equivalence *)
//                    / "<=>"                                   (* equivalence *)
//                    / "<|>"                                   (* concurrent equivalence *)
//                    / "=/>"                                   (* predictive implication *)
//                    / "==>"                                   (* implication *)
//                    / "=\\>"                                  (* =\> retrospective implication *)
//                    / "=|>"                                   (* concurrent implication *)
//                    / "{--"                                   (* instance *)
//                    / "{-]"                                   (* instance-property *)
//
//             term ::= word                                    (* an atomic constant term *)
//                    / variable                                (* an atomic variable term *)
//                    / compound-term                           (* a term with internal structure *)
//                    / statement                               (* a statement can serve as a term *)
//
//        op-ext-set::= "{"                                     (* extensional set *)
//        op-int-set::= "["                                     (* intensional set *)
//       op-negation::= "--"                                    (* negation *)
//      op-int-image::= "\\"                                    (* \ intensional image *)
//      op-ext-image::= "/"                                     (* extensional image *)
//         op-multi ::= "&&"                                    (* conjunction *)
//                    / "*"                                     (* product *)
//                    / "||"                                    (* disjunction *)
//                    / "&|"                                    (* parallel events *)
//                    / "&/"                                    (* sequential events *)
//                    / "|"                                     (* intensional intersection *)
//                    / "&"                                     (* extensional intersection *)
//        op-single ::= "-"                                     (* extensional difference *)
//                    / "~"                                     (* intensional difference *)
//    compound-term ::= op-ext-set term {"," term} "}"          (* extensional set *)
//                    / op-int-set term {"," term} "]"          (* intensional set *)
//                    / "("op-multi"," term {"," term} ")"      (* with prefix operator *)
//                    / "("op-single"," term "," term ")"       (* with prefix operator *)
//                    / "(" term {op-multi term} ")"            (* with infix operator *)
//                    / "(" term op-single term ")"             (* with infix operator *)
//                    / "(" term {","term} ")"                  (* product, new notation *)
//                    / "(" op-ext-image "," term {"," term} ")"(* special case, extensional image *)
//                    / "(" op-int-image "," term {"," term} ")"(* special case, \ intensional image *)
//                    / "(" op-negation "," term ")"            (* negation *)
//                    / op-negation term                        (* negation, new notation *)
//
//        statement ::= <"<">term copula term<">">              (* two terms related to each other *)
//                    / <"(">term copula term<")">              (* two terms related to each other, new notation *)
//                    / term                                    (* a term can name a statement *)
//                    / "(^"word {","term} ")"                  (* an operation to be executed *)
//                    / word"("term {","term} ")"               (* an operation to be executed, new notation *)
//
//         variable ::= "$"word                                 (* independent variable *)
//                    / "#"word                                 (* dependent variable *)
//                    / "?"word                                 (* query variable in question *)
//
//            tense ::= ":/:"                                   (* future event *)
//                    / ":|:"                                   (* present event *)
//                    / ":\\:"                                  (* :\: past event *)
//
//           desire ::= truth                                   (* same format, different interpretations *)
//            truth ::= <"%">frequency[<";">confidence]<"%">    (* two numbers in [0,1]x(0,1) *)
//           budget ::= <"$">priority[<";">durability][<";">quality]<"$"> (* three numbers in [0,1]x(0,1)x[0,1] *)
//
//               word : #"[^\ ]+"                               (* unicode string *)
//           priority : #"([0]?\.[0-9]+|1\.[0]*|1|0)"           (* 0 <= x <= 1 *)
//         durability : #"[0]?\.[0]*[1-9]{1}[0-9]*"             (* 0 <  x <  1 *)
//            quality : #"([0]?\.[0-9]+|1\.[0]*|1|0)"           (* 0 <= x <= 1 *)
//          frequency : #"([0]?\.[0-9]+|1\.[0]*|1|0)"           (* 0 <= x <= 1 *)
//         confidence : #"[0]?\.[0]*[1-9]{1}[0-9]*"             (* 0 <  x <  1 *)
sealed interface NarsParserFSM : CharParser {
    val name: String
    val metaType: TypeMemento

}


object term : CharParser by skipws_(
    word or  //an atomic constant term
            variable or  //an atomic variable term
            compound_term or  //a term with internal structure
            statement
)  //a statement can serve as a term


object compound_term : CharParser by skipws_(
    allOf(char_('('), term, copula, term, char_(')')) or  //two terms related to each other
            term or  //a term can name a statement
            allOf(
                char_('('),
                char_('^'),
                word,
                repeat_(allOf(char_(','), term)),
                char_(')')
            ) or  //an operation to be executed
            allOf(word, char_('('), term, repeat_(allOf(char_(','), term)), char_(')'))
)  //an operation to be executed


object statement : CharParser by skipws_(
    concAllOf(
        char_('('),
        statement,
        copula,
        statement,
        char_(')')
    ) or  //two statements related to each other
            term or  //a term can name a statement
            concAllOf(
                char_('('),
                char_('^'),
                word,
                repeat_(allOf(char_(','), term)),
                char_(')')
            ) or  //an operation to be executed
            concAllOf(word, char_('('), term, repeat_(allOf(char_(','), term)), char_(')'))
)  //an operation to be executed


object variable : CharParser by skipws_(
    (char_('$') + word) /  //independent variable
            (char_('#') + word) /  //dependent variable
            (char_('?') + word)
)  //query variable in question


object budget : CharParser by skipws_(char_('%') and number)  //a budget value

object word :
    CharParser by skipws_((!whitespace)[-1])  //word is a sequence of non-whitespace characters


object copula : CharParser by skipws_(
    string_("-->") /  //inheritance
            string_("--]") /  //property
            string_("<->") /  //similarity
            string_("</>") /  //predictive equivalence
            string_("<=>") /  //equivalence
            string_("<|>") /  //concurrent equivalence
            string_("=/>") /  //predictive implication
            string_("==>") /  //implication
            string_("=\\>") /  //=\> retrospective implication
            string_("=|>") /  //concurrent implication
            string_("{--") /  //instance
            string_("{-]")
)  //instance-property

object tense : CharParser by skipws_(
    string_(":/:") /  //future event
            string_(":|:") /  //present event
            string_(":\\:")
)  //\: past event

object desire : CharParser by skipws_(truth)  //same format, different interpretations

object truth : CharParser by skipws_(char_('%') and number)  //a truth value

object priority : CharParser by skipws_(number)  //a priority value

object durability : CharParser by skipws_(number)  //a durability value

object quality : CharParser by skipws_(number)  //a quality value

object frequency : CharParser by skipws_(number)  //a frequency value

object confidence : CharParser by skipws_(number)  //a confidence value

object number : CharParser by skipws_(chgroup_.digit[-1])  //a number is a sequence of digits

//        op-ext-set::= "{"                                     (* extensional set *)
//        op-int-set::= "["                                     (* intensional set *)
//       op-negation::= "--"                                    (* negation *)
//      op-int-image::= "\\"                                    (* \ intensional image *)
//      op-ext-image::= "/"                                     (* extensional image *)
//         op-multi ::= "&&"                                    (* conjunction *)
//                    / "*"                                     (* product *)
//                    / "||"                                    (* disjunction *)
//                    / "&|"                                    (* parallel events *)
//                    / "&/"                                    (* sequential events *)
//                    / "|"                                     (* intensional intersection *)
//                    / "&"                                     (* extensional intersection *)
//        op-single ::= "-"                                     (* extensional difference *)
//                    / "~"                                     (* intensional difference *)
//    compound-term ::= op-ext-set term {"," term} "}"          (* extensional set *)
//                    / op-int-set term {"," term} "]"          (* intensional set *)
//                    / "("op-multi"," term {"," term} ")"      (* with prefix operator *)
//                    / "("op-single"," term "," term ")"       (* with prefix operator *)
//                    / "(" term {op-multi term} ")"            (* with infix operator *)
//                    / "(" term op-single term ")"             (* with infix operator *)
//                    / "(" term {","term} ")"                  (* product, new notation *)
//                    / "(" op-ext-image "," term {"," term} ")"(* special case, extensional image *)
//                    / "(" op-int-image "," term {"," term} ")"(* special case, \ intensional image *)
//                    / "(" op-negation "," term ")"            (* negation *)
//                    / op-negation term                        (* negation, new notation *)

object op_ext_set : CharParser by skipws_(char_('{'))  //extensional set

object op_int_set : CharParser by skipws_(char_('['))  //intensional set

object op_multi : CharParser by skipws_(
    string_("&&") /  //conjunction
            string_("*") /  //product
            string_("||") /  //disjunction
            string_("&|") /   //parallel events
            string_("&/") /  //sequential events
            string_("|") /  //intensional intersection
            string_("&")
)  //extensional intersection

object op_single : CharParser by skipws_(
    char_('-') or  //extensional difference
            char_('~')
)  //intensional difference

object op_ext_image : CharParser by skipws_(char_('/'))  //extensional image

object op_int_image : CharParser by skipws_(char_('\\'))  //\ intensional image

object op_negation : CharParser by skipws_(string_("--"))  //negation

object whitespace :
    CharParser by skipws_(char_(' ') or char_('\t') or char_('\r') or char_('\n'))  //whitespace

