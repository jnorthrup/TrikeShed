package borg.trikeshed.userspace.nio.platform.spi

/** geteuid(2): the effective user id that owns what this process creates. */
expect fun platformGeteuid(): UInt
