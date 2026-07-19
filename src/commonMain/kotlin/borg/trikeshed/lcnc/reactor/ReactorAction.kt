package borg.trikeshed.lcnc.reactor

import borg.trikeshed.context.nuid.Nuid
import borg.trikeshed.lcnc.isam.LcncEntity
import borg.trikeshed.lib.Join

/**
 * ReactorAction represents the invocation request envelope for LCNC ingest pipeline lifecycle.
 * As defined in todo.md: ReactorAction and ReactorResult as Join<Nuid, Join<Verb, Payload>>
 * The sealed class encodes the CCEK lifecycle states.
 */
sealed class ReactorAction {
    data class Opened(val nuid: Nuid) : ReactorAction()
    data class Activated(val nuid: Nuid) : ReactorAction()
    data class PublishEntity(val nuid: Nuid, val entity: LcncEntity) : ReactorAction()
    data class Draining(val nuid: Nuid) : ReactorAction()
    data class Closed(val nuid: Nuid) : ReactorAction()
    
    companion object {
        fun opened(nuid: Nuid) = Opened(nuid)
        fun activated(nuid: Nuid) = Activated(nuid)
        fun publishEntity(nuid: Nuid, entity: LcncEntity) = PublishEntity(nuid, entity)
        fun draining(nuid: Nuid) = Draining(nuid)
        fun closed(nuid: Nuid) = Closed(nuid)
    }
}

sealed class ReactorResult {
    data class Ok(val nuid: Nuid, val payload: ByteArray) : ReactorResult()
    data class Error(val nuid: Nuid, val message: String) : ReactorResult()
}

typealias ReactorActionAlias = Join<Nuid, Join<String, Any?>> // Legacy typealias for compatibility
typealias ReactorResultAlias = Join<Nuid, Join<String, Any?>>