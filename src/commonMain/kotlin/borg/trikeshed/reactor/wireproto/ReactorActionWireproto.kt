package borg.trikeshed.reactor.wireproto

import borg.trikeshed.lcnc.reactor.ReactorAction
import borg.trikeshed.isam.FieldSynapse
import borg.trikeshed.isam.Phase
import borg.trikeshed.lcnc.isam.LcncEntity
import borg.trikeshed.lcnc.isam.LcncPage
import borg.trikeshed.lib.j

// Constants for Opcodes
const val OPCODE_OPENED = 0x50u
const val OPCODE_ACTIVATED = 0x51u
const val OPCODE_PUBLISH_ENTITY = 0x52u
const val OPCODE_DRAINING = 0x53u
const val OPCODE_CLOSED = 0x54u

/**
 * Encodes a ReactorAction into a FieldSynapse.
 */
fun ReactorAction.toFieldSynapse(): FieldSynapse {
    return when (this) {
        is ReactorAction.Opened -> FieldSynapse(
            phase = Phase.INIT.int,
            opcode = OPCODE_OPENED,
            methodIdx = 0,
            addr = 0,
            seq = 0,
            nano = 0,
            callsiteHash = 0u,
            templateIdx = 0
        )
        is ReactorAction.Activated -> FieldSynapse(
            phase = Phase.EXECUTE.int,
            opcode = OPCODE_ACTIVATED,
            methodIdx = 0,
            addr = 0,
            seq = 0,
            nano = 0,
            callsiteHash = 0u,
            templateIdx = 0
        )
        is ReactorAction.PublishEntity -> FieldSynapse(
            phase = Phase.DISPATCH.int,
            opcode = OPCODE_PUBLISH_ENTITY,
            methodIdx = 0,
            addr = 0,
            seq = 0,
            nano = 0, // Should be time?
            callsiteHash = 0u,
            templateIdx = 0
        ) // LcncEntity needs separate payload handling, but wireproto 24B struct only fits metadata
        is ReactorAction.Draining -> FieldSynapse(
            phase = Phase.COMPLETE.int,
            opcode = OPCODE_DRAINING,
            methodIdx = 0,
            addr = 0,
            seq = 0,
            nano = 0,
            callsiteHash = 0u,
            templateIdx = 0
        )
        is ReactorAction.Closed -> FieldSynapse(
            phase = Phase.RECLAIM.int,
            opcode = OPCODE_CLOSED,
            methodIdx = 0,
            addr = 0,
            seq = 0,
            nano = 0,
            callsiteHash = 0u,
            templateIdx = 0
        )
    }
}

/**
 * Decodes a FieldSynapse back into a ReactorAction.
 * Note: PublishEntity requires additional payload for full LcncEntity reconstruction.
 */
fun FieldSynapse.toReactorAction(entityPayload: LcncEntity? = null): ReactorAction {
    // Need a dummy Nuid for reconstruction since wireproto doesn't carry Nuid
    val dummyNuid = borg.trikeshed.context.nuid.nuid(
        borg.trikeshed.context.nuid.Capability.Custom("wireproto", "decode"),
        borg.trikeshed.context.nuid.Nonce.RandomBytes(),
        borg.trikeshed.context.nuid.Subnet.core
    )
    
    return when (opcode) {
        OPCODE_OPENED -> ReactorAction.opened(dummyNuid)
        OPCODE_ACTIVATED -> ReactorAction.activated(dummyNuid)
        OPCODE_PUBLISH_ENTITY -> ReactorAction.publishEntity(
            dummyNuid,
            entityPayload ?: LcncPage(
                id = "dummy", 
                title = "dummy", 
                parentId = null, 
                contentBlocks = 0 j { throw Exception("dummy") }
            )
        )
        OPCODE_DRAINING -> ReactorAction.draining(dummyNuid)
        OPCODE_CLOSED -> ReactorAction.closed(dummyNuid)
        else -> throw IllegalArgumentException("Unknown opcode for ReactorAction: $opcode")
    }
}