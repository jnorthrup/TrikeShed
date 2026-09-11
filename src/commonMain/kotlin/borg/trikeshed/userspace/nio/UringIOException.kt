package borg.trikeshed.userspace.nio

import borg.trikeshed.userspace.UringOp

/** Negative completion result, retained for callers that distinguish ENOENT from other failures. */
class UringIOException(val operation: UringOp, val result: Int, detail: String = "") :
    IOException("$operation failed: $result" + if (detail.isEmpty()) "" else ": $detail")
