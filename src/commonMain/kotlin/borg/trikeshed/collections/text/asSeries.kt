package borg.trikeshed.collections.text

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toSeries

/** Series of Chars from a CharSequence — use root's CharSequence.toSeries() */
@Suppress("unused")
fun CharSequence.asSeries(): Series<Char> = toSeries()
