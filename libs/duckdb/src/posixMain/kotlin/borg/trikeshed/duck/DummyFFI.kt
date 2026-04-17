@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
package borg.trikeshed.duck

import kotlinx.cinterop.*

@CName("trikeshed_ping")
fun ping(): Int {
    return 42
}
