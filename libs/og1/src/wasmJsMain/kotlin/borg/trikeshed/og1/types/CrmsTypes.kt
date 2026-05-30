package borg.trikeshed.og1.types

import kotlin.js.JsName
import kotlin.random.Random

@JsName("performance.now")
external fun performanceNowMs(): Double

actual fun generateOg1Id(): String =
    Random.nextBytes(4).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

actual fun currentTimeSeconds(): Double = performanceNowMs() / 1000.0
