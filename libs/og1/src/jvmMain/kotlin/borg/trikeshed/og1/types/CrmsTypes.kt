@file:JvmName("CrmsTypesPlatform")

package borg.trikeshed.og1.types

import java.util.UUID

actual fun generateOg1Id(): String = UUID.randomUUID().toString().take(8)
actual fun currentTimeSeconds(): Double = System.currentTimeMillis() / 1000.0
