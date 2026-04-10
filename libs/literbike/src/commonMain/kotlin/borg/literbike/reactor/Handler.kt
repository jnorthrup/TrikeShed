/**
 * Port of /Users/jim/work/literbike/src/reactor/handler.rs
 *
 * Event Handler Trait — minimal handler interface for reactor events.
 */
package borg.literbike.reactor

import java.io.IOException

/**
 * Mirrors Rust trait: `pub trait EventHandler: Send + Sync`
 *
 * Event handler trait — implement for I/O event handling.
 */
interface EventHandler {
    fun onReadable(fd: Int)
    fun onWritable(fd: Int)
    fun onError(fd: Int, error: IOException)
}

/**
 * Mirrors Rust struct: `pub struct NullHandler`
 *
 * Null handler for testing.
 */
object NullHandler : EventHandler {
    override fun onReadable(fd: Int) {}
    override fun onWritable(fd: Int) {}
    override fun onError(fd: Int, error: IOException) {}
}
