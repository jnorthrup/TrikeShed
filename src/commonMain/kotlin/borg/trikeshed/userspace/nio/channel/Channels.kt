package borg.trikeshed.userspace.nio.channel

import borg.trikeshed.userspace.ChannelsImpl
import borg.trikeshed.userspace.FileImpl
import borg.trikeshed.userspace.nio.file.File
import borg.trikeshed.userspace.openUserspaceChannelBackend
import borg.trikeshed.userspace.FunctionalUringFacade

/**
 * Channel factory — backed by expect/actual [ChannelsImpl].
 */
object Channels {
    fun open(entries: Int = 256): Channel =
        Channel(FunctionalUringFacade(entries, openUserspaceChannelBackend(entries)))

    fun socket(domain: Int, type: Int, protocol: Int): File =
        File(ChannelsImpl.socket(domain, type, protocol))
}