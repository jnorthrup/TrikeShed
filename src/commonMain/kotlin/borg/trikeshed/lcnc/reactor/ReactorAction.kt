package borg.trikeshed.lcnc.reactor

import borg.trikeshed.context.nuid.Nuid
import borg.trikeshed.lib.Join

/**
 * ReactorAction represents the invocation request envelope.
 * As defined in todo.md: ReactorAction and ReactorResult as Join<Nuid, Join<Verb, Payload>>
 */
typealias ReactorAction = Join<Nuid, Join<String, Any?>> // Using String for Verb and Any? for Payload as a placeholder
typealias ReactorResult = Join<Nuid, Join<String, Any?>>
