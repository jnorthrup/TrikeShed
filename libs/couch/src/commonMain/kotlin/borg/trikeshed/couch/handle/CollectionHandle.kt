package borg.trikeshed.couch.handle

import borg.trikeshed.couch.miniduck.MiniRowVec
import borg.trikeshed.lib.Series

/** Multiplatform declarations — platform-specific implementations provided in platform source sets */
// Using top-level HandleState enum; platform-specific actual provided in jvmMain

expect class CollectionHandle {
    val state: HandleState
    val rowCount: Int
    companion object {
        fun open(): CollectionHandle
    }
    fun append(row: MiniRowVec)
    fun seal()
    fun close()
    fun snapshot(): Series<MiniRowVec>
}

