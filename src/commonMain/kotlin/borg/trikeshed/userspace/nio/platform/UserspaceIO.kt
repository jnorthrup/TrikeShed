package borg.trikeshed.userspace.nio.platform

import borg.trikeshed.userspace.nio.channel.Channel
import borg.trikeshed.userspace.nio.file.File
import borg.trikeshed.userspace.SelectionResult
import borg.trikeshed.userspace.nio.ByteBuffer

/**
 * Compatibility names for the old userspace IO facade.
 * New code should use the NIO symbols directly.
 */

@Deprecated("Use borg.trikeshed.userspace.nio.ByteBuffer.", ReplaceWith("ByteBuffer"))
typealias UserspaceBuffer = ByteBuffer

@Deprecated("Use borg.trikeshed.userspace.nio.file.File.", ReplaceWith("File"))
typealias UserspaceFD = File

@Deprecated("Use SelectionResult.", ReplaceWith("SelectionResult"))
typealias UserspaceIOResult = SelectionResult

@Deprecated("Use borg.trikeshed.userspace.nio.channel.Channel.", ReplaceWith("Channel"))
typealias UserspaceRing = Channel
