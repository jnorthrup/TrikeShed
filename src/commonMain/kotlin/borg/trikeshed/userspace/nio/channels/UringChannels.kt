package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.ChannelsImpl
import borg.trikeshed.userspace.FileImpl
import borg.trikeshed.userspace.nio.file.File
import borg.trikeshed.userspace.openUserspaceChannelBackend
import borg.trikeshed.userspace.FunctionalUringFacade

/**
 * Channel factory — backed by expect/actual [ChannelsImpl].
 */
object UringChannels {
    fun open(entries: Int = 256): UringChannel =
        UringChannel(FunctionalUringFacade(entries, openUserspaceChannelBackend(entries)))

    fun socket(domain: Int, type: Int, protocol: Int): File =
        File(ChannelsImpl.socket(domain, type, protocol))
}