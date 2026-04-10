package borg.literbike.channel

/**
 * Port target for AbstractChannelProvider.kt
 *
 * Mirrors the Rust trait `pub trait AbstractChannelProvider`.
 */
interface AbstractChannelProvider {
    fun openChannel(name: String): Boolean
}
