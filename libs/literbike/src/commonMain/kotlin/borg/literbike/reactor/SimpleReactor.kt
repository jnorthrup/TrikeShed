/**
 * Port of /Users/jim/work/literbike/src/reactor/simple_reactor.rs
 *
 * Minimal reactor stub.
 */
package borg.literbike.reactor

/**
 * Mirrors Rust struct: `pub struct SimpleReactor`
 */
class SimpleReactor {
    companion object {
        fun create(): SimpleReactor = SimpleReactor()
    }

    fun runOne(): Boolean = true
}
