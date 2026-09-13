package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.file.File

/** Socket factory over the expect/actual [ChannelsImpl]; channels live in [borg.trikeshed.userspace.nio.channels.UringChannels]. */
object Channels {
    fun socket(domain: Int, type: Int, protocol: Int): File =
        File.fromImpl(ChannelsImpl.socket(domain, type, protocol))
}
