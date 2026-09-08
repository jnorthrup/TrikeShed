package borg.trikeshed.platform

import borg.trikeshed.lib.os
import borg.trikeshed.lib.processObj
import kotlin.time.Clock.System

actual fun loadPlatformHost(): PlatformHost {
    val process = processObj
    val nodeOs = if (jsNodeProcess(process)) os else null
    val navigator = try {
        js("typeof navigator !== 'undefined' ? navigator : null")
    } catch (_: dynamic) { null }
    val browser = try {
        js("typeof window !== 'undefined' && typeof document !== 'undefined' || typeof WorkerGlobalScope !== 'undefined' && typeof self !== 'undefined' && self instanceof WorkerGlobalScope") as Boolean
    } catch (_: dynamic) { false }
    val hosted = try {
        js("typeof Graal !== 'undefined' || typeof Polyglot !== 'undefined' || typeof Java !== 'undefined' && typeof Java.type === 'function'") as Boolean
    } catch (_: dynamic) { false }
    return object : PlatformHost {
        override val clock: PlatformClock = object : PlatformClock {
            override fun nowMillis(): Long = kotlin.js.Date.now().toLong()
            override fun monotonicNanos(): Long = System.now().toEpochMilliseconds() * 1000000L
        }
        override val processors: Int = jsHostProcessors(nodeOs, navigator)
        override val descriptor: HostDescriptor = jsHostDescriptor(process, nodeOs, browser, hosted)
    }
}
