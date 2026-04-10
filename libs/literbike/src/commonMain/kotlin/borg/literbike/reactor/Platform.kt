/**
 * Port of /Users/jim/work/literbike/src/reactor/platform.rs
 *
 * Platform I/O factory abstraction for reactor components.
 */
package borg.literbike.reactor

import java.io.IOException

/**
 * Mirrors Rust trait: `pub trait PlatformIO`
 */
interface PlatformIO {
    fun createSelector(): Result<ManualSelector>
}

/**
 * Mirrors Rust struct: `#[derive(Debug, Clone, Copy, Default)] pub struct PortablePlatformIO`
 */
data object PortablePlatformIO : PlatformIO {
    override fun createSelector(): Result<ManualSelector> =
        Result.success(ManualSelector())
}
