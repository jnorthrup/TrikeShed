package borg.trikeshed.userspace.nio.channels.spi

class PosixChannelOperations : ChannelOperations {
    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle = PosixChannelHandle(entries)

    /** The descriptor is borrowed; prepClose explicitly closes it. */
    fun handleFor(fd: Int): ChannelOperations.ChannelHandle = PosixChannelHandle(256, fd)
}
