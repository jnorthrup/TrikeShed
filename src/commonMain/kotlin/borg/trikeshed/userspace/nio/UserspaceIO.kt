package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer

/**
 * Compatibility names for the old userspace IO facade.
 * New code should use the NIO symbols directly.
 */

@Deprecated("Use borg.trikeshed.userspace.nio.ByteBuffer.", ReplaceWith("ByteBuffer"))
typealias UserspaceBuffer = ByteBuffer

@Deprecated("Use borg.trikeshed.userspace.File.", ReplaceWith("File"))
typealias UserspaceFD = File

@Deprecated("Use SelectionResult.", ReplaceWith("SelectionResult"))
typealias UserspaceIOResult = SelectionResult

@Deprecated("Use borg.trikeshed.userspace.Channel.", ReplaceWith("Channel"))
typealias UserspaceRing = Channel
