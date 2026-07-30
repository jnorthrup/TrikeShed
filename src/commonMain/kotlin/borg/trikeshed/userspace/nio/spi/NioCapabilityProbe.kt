package borg.trikeshed.userspace.nio.spi

/**
 * Platform-specific probe for the native I/O backend that will execute I/O.
 *
 * This is separate from [platformNioProviders] so server-rendered dashboards
 * can report launch-time capability without fully instantiating the supervisor.
 */
expect fun currentNioCapabilityReport(): NioCapabilityReport
