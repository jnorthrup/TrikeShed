package borg.trikeshed.userspace.nio.channels.spi

/** Linux effects are selected by the backend beneath the common ring. */
class LinuxChannelOperations : ChannelOperations {
    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle = PosixChannelHandle(entries)
}
