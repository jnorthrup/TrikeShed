@file:JvmName("UserspaceNetworkModule")

package borg.literbike.userspace_network

/**
 * Network abstractions and protocol adapters
 *
 * This module provides unified network protocol handling with adapters
 * for HTTP, QUIC, SSH, and other protocols.
 */

// Re-exports
typealias NetworkAdapter = borg.literbike.userspace_network.NetworkAdaptersModule.NetworkAdapter
typealias AdapterType = borg.literbike.userspace_network.NetworkAdaptersModule.AdapterType
typealias HttpAdapter = borg.literbike.userspace_network.NetworkAdaptersModule.HttpAdapter
typealias QuicAdapter = borg.literbike.userspace_network.NetworkAdaptersModule.QuicAdapter
typealias SshAdapter = borg.literbike.userspace_network.NetworkAdaptersModule.SshAdapter
typealias AdapterFactory = borg.literbike.userspace_network.NetworkAdaptersModule.AdapterFactory
typealias Channel = borg.literbike.userspace_network.ChannelsModule.Channel
typealias ChannelProvider = borg.literbike.userspace_network.ChannelsModule.ChannelProvider
typealias TcpChannel = borg.literbike.userspace_network.ChannelsModule.TcpChannel
typealias TcpChannelProvider = borg.literbike.userspace_network.ChannelsModule.TcpChannelProvider
typealias ChannelMetadata = borg.literbike.userspace_network.ChannelsModule.ChannelMetadata
typealias Protocol = borg.literbike.userspace_network.ProtocolsModule.Protocol
typealias ProtocolDetector = borg.literbike.userspace_network.ProtocolsModule.ProtocolDetector
