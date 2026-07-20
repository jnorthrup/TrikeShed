package borg.trikeshed.reactor

import borg.trikeshed.lib.Join

// Note: Verb and Payload are already defined in ReactorEndpoint.kt.
// typealias Verb = String
// typealias Payload = ByteArray

typealias ChannelMessage = Join<Protocol, Join<Verb, Payload>>
