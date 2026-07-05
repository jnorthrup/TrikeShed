package borg.trikeshed.usersignals.platform

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun formatDouble(value: Double, decimals: Int): String = "%.${decimals}f".format(value)