package borg.literbike.htxke.key

import borg.literbike.htxke.*

/**
 * CCEK Key - 1:1 with Element, passive SDK provider
 *
 * Key trait - 1:1 with Element
 */
interface CcekKey<E : Element> : Key<E>

// Re-export protocol-specific keys from elements
val HtxKey: Key<HtxElement> get() = borg.literbike.htxke.HtxKey
val QuicKey: Key<QuicElement> get() = borg.literbike.htxke.QuicKey
val HttpKey: Key<HttpElement> get() = borg.literbike.htxke.HttpKey
val SctpKey: Key<SctpElement> get() = borg.literbike.htxke.SctpKey
val NioKey: Key<NioElement> get() = borg.literbike.htxke.NioKey

typealias HtxElement = borg.literbike.htxke.HtxElement
typealias QuicElement = borg.literbike.htxke.QuicElement
typealias HttpElement = borg.literbike.htxke.HttpElement
typealias SctpElement = borg.literbike.htxke.SctpElement
typealias NioElement = borg.literbike.htxke.NioElement
