package borg.trikeshed.og1.types

import kotlin.random.Random

actual fun generateOg1Id(): String =
    Random.nextBytes(4).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

actual fun currentTimeSeconds(): Double =
    kotlin.js.Date().getTime().unsafeCast<Double>() / 1000.0
