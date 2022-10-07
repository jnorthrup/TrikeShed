package borg.trikeshed.placeholder.nars.inference

import  borg.trikeshed.lib.CowSeriesHandle  as COWSeries
        import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.ExperimentalTime


/* this module handles a parser pipeline for the NARSese language
* 
* in kotlin the Flow class is a coroutine-based pipeline, and we want to share the same pipeline for all parsers at once 
*  in a hierarchy of Jobs coroutines.
* 
* we want a hierarchy of single byte Flow<Byte> propagating from the root of the hierarchy to parser Jobs running 
* simulataneous. 
* 
* We want a hierarchical collection of signalling parser state through CoroutineContext.Elements to greedily bubble 
* up the longest parser matches to the root of the hierarchy. 
* 
* when a Recognizer turns "green" it should signal to the parent that it has a match, and the parent should 
* signal to the root that it has a match.  repeating recognizers "grow" a green result by repeating until the condition ceases to consume input.
* 
* nodes that are green and cease to consume input should signal to the parent that they are "done".  The Recognizer needs to be able to 
* cancel receiving input from the parent.
* 
* 
* We want to be able to cancel a single parser without affecting the others.  the choice of Flows to use are 
* StateFlow, SharedFlow, BroadcastChannel, and MutableSharedFlow. StateFlow is a single value, SharedFlow is a
* single consumer, BroadcastChannel is a single producer, and MutableSharedFlow is a single producer and single consumer.
* 
* MutableSharedFlow is the best choice for a single producer and single consumer.  it is a single producer and single consumer
* because the parent parser is the producer and the child parser is the consumer.  
* 
* we use the MutableSharedFlow to propagate the input byte from the parent to the child.  the child parser can cancel
* the subscription to the parent parser when it is done.  the parent parser can cancel the subscription to the child parser
* when it is done.  the parent parser can cancel the child parser Job when it is done.  
* 
* when the Job cooroutine is cancelled the parent needs to check if the child is green and by default use a greedy 
* strategy to consume the child's match.
* 
* parser combinators are used to build the parser tree.  the parser tree is a tree of Recognizers.  
* operator overloads are used to line up potential Recognizer starting points with the input byte stream.  the
* parse tree is mostly static and is built at compile time.  srongly favoring the root level object as the starting
* point for the parser tree.  the parser tree is a tree of Recognizers.  the Recognizer is a sealed class
* with subclasses for the different types of Recognizers.
*  
*/


import borg.trikeshed.lib.*
import borg.trikeshed.lib.CowSeriesHandle
import borg.trikeshed.lib.collections.s_
import kotlinx.coroutines.flow.*
import kotlin.coroutines.CoroutineContext
import kotlin.properties.Delegates
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty0


inline fun <reified T:Recognizer> parser(): Recognizer =object : Recognizer {
    override val children: CowSeriesHandle<Recognizer> = Series.empty<Recognizer>().cow
    override val green: Boolean = true
    val onGreen: KProperty0<Boolean> by  Delegates.observable(this::green) { _: KProperty<*>, oldValue: KProperty0<Boolean>, newValue: KProperty0<Boolean> ->
        Join(oldValue,newValue)
    }
}

/**
 * letter and envelope COWSeries of Recognizers
 *
 * operator  Recognizer.plus(r:Recognizer):Recognizer  // Ordered(this,r)
 * operator  Recognizer.minus(r:Recognizer):Recognizer //remove r from children
 * operator  Recognizer.times(r:Recognizer):Recognizer //append optional(r)
 * operator  Recognizer.!(r:Recognizer):Recognizer //append optional(r)
 * operator  Unit.times(r:Recognizer):Recognizer       //replace envelope with  optional(this) + r
 * operator  Recognizer.div(r:Recognizer):Recognizer   // returns AnyOf(this.flatmap,r.flatmap)
 * operator  Recognizer.get(r:Int):Recognizer   // returns Repeat(r,this)
 * operator  Recognizer.get(r:IntRange):Recognizer   // returns Repeat(r.first,this)+repeat(r.last-r.first,optional(this))
 * operator  Recognizer.in(r:Recognize):Recognizer   // returns ordered( r,this, r)
 * operator  Recognizer.in(r:Twin<Recognize>):Recognizer   // returns ordered( r.a,this, r.b)
 * operator  Recognizer.%(r:Recognize):Recognizer   // returns ordered( AnyOf(this,(r+not(this)))[1])
 * */

interface Recognizer {
    val input: Flow<Byte>
    val green: Boolean
    val children: COWSeries<Recognizer>
}


/**
 * input is only shared with one child, starting at index0, until onGreen.
 * this parent turns green when onGreen is true for each index of children in order.
 *
 */
class Ordered (c:Series <Recognizer> ): Recognizer,CoroutineContext.Element {

    val mutableSeries = Series.empty<Recognizer>().cow + this + c
    override val children: CowSeriesHandle<Recognizer> =    mutableSeries
}

class AnyOf(val a: Recognizer, val b: Recognizer): Recognizer {
    override val green: Boolean = a.green || b.green
    override val children: COWSeries<Recognizer> =s_[a,b].cow
}

class Repeat(val count: Int, val r: Recognizer): Recognizer {
    override val green: Boolean = (count == 0) || r.green
    override val children: COWSeries<Recognizer> = Ordered(listOf<Recognizer>(count) { r }).toSeries() .cow
}

class Optional(val r: Recognizer): Recognizer {
    override val green: Boolean = true
    override val children: COWSeries<Recognizer> = Series(r).cow
}

class Letter(val l: Char): Recognizer {
    override val green: Boolean = true
    override val children: COWSeries<Recognizer> = Series.empty<Recognizer>().cow
}

class Envelope(val r: Recognizer): Recognizer {
    override val green: Boolean = true
    override val children: COWSeries<Recognizer> = Series(r).cow
}

operator fun Recognizer.plus(r: Recognizer): Recognizer = Ordered(this,r)
operator fun Recognizer.minus(r: Recognizer): Recognizer = Ordered(this,Optional(r))
operator fun Recognizer.times(r: Recognizer): Recognizer = Ordered(this,Optional(r))
operator fun Recognizer.div(r: Recognizer): Recognizer = AnyOf(this,r)
operator fun Recognizer.get(r: Int): Recognizer = Repeat(r,this)
operator fun Recognizer.get(r: IntRange): Recognizer = Repeat(r.first,this)+Repeat(r.last-r.first,Optional(this))
operator fun Recognizer.in(r: Recognizer): Recognizer = Ordered( r,this, r)
operator fun Recognizer.in(r: Twin<Recognizer>): Recognizer = Ordered( r.a,this, r


/** Flowtree is the top level FSM for the parser pipeline.  it is a coroutine that runs in the root Job.  The FSM receives a list of lines of NARSese
 * and parses them into a list of NarseseStatements by feeding a byte at a time into the parser pipeline from MutableSharedFlow<Byte>.

each chlid of the Flowtree coroutine is Recognizer coroutine launched in a seperate Job that is a child of the Flowtree coroutine.

the Flowtree coroutine is the parent of all the Recognizer coroutines.  the Flowtree coroutine is the producer of the input byte stream.

the Combinators are assembled with operator overload expressions that are evaluated at compile time.

the FlowTree is the root of the thierarchy.  the combinators provide fanout to multiple concurrrent parse pipelines.

Combinators and RecognizerNodes are Recognizer intefaces which are passed in the parents reference to a MutableSharedFlow<Byte> from shareIn method call at initialization time.

 */

@ExperimentalTime
@ExperimentalCoroutinesApi
class FlowTree() : FlowCollector<Flow<String>> {

    override suspend fun emit(value: Flow<String>) = runBlocking {
        //start a new root job
        val rootJob = launch {
            value.collect { s: String ->
                val ss:Series<Char> = s.toSeries()


                @Suppress("UNCHECKED_CAST")
                val mutableSharedFlow = MutableSharedFlow<Byte>(replay = 0).emitAll(ss.`▶`.asFlow() as Flow<Byte>)



            }

                SharedMutableFlow(ss.)