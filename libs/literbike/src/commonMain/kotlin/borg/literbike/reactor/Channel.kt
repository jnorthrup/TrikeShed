/**
 * Port of /Users/jim/work/literbike/src/reactor/channel.rs
 *
 * Channel traits for the portable reactor abstraction.
 */
package borg.literbike.reactor

import java.io.IOException

/**
 * Mirrors Rust trait: `pub trait SelectableChannel: Send`
 */
interface SelectableChannel {
    fun rawFd(): Int
    fun isOpen(): Boolean
    fun close(): Result<Unit>
}

/**
 * Mirrors Rust trait: `pub trait ReadableChannel: SelectableChannel {}`
 */
interface ReadableChannel : SelectableChannel

/**
 * Mirrors Rust trait: `pub trait WritableChannel: SelectableChannel {}`
 */
interface WritableChannel : SelectableChannel

/**
 * Mirrors Rust struct: `pub struct ChannelRegistration`
 */
data class ChannelRegistration(
    val fd: Int,
    val interests: InterestSet,
) {
    companion object {
        fun of(fd: Int, interests: InterestSet): ChannelRegistration =
            ChannelRegistration(fd, interests)
    }
}
