package borg.trikeshed.usersignals.platform

actual fun currentTimeMillis(): Long = (js("Date.now()") as Double).toLong()

actual fun formatDouble(value: Double, decimals: Int): String = value.asDynamic().toFixed(decimals) as String
