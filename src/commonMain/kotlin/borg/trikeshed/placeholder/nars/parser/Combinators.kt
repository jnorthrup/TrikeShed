package borg.trikeshed.placeholder.nars.parser


import borg.trikeshed.lib.*
import kotlin.properties.Delegates
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty0


inline fun <reified T:Recognizer> parser(): Recognizer =object : Recognizer {
    val children = Series.empty<T>().cow
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
 * operator  Recognizer.%(r:Recognize):Recognizer   // returns ordered( AnyOf(this,r)[1])
 * */

