package borg.trikeshed.pointcut

import borg.trikeshed.lib.MutableSeries

/** Compatibility surface for legacy pointcut tests that live in this package. */
val <T> MutableSeries<T>.size: Int get() = a

/** Compatibility surface for legacy pointcut tests that live in this package. */
operator fun <T> MutableSeries<T>.get(index: Int): T = b(index)
