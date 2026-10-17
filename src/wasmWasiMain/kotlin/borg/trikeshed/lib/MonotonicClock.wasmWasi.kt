package borg.trikeshed.lib

import borg.trikeshed.userspace.wasi.Wasi

actual fun monotonicNowMillis(): Long = Wasi.monotonicNanos() / 1_000_000L
