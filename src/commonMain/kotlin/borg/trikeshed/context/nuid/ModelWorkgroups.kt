package borg.trikeshed.context.nuid

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * ModelWorkgroups — production [Workgroup] registration for the model facades.
 *
 * The canonical registration pattern lives in `JvmKanbanServer.run` (jvmMain):
 * build a [TraitSpace] over the capabilities the group fulfills, wrap it in a
 * [Workgroup] with a [Subnet] scope, `register` it on a [NuidFanoutElement],
 * then `activate` the fanout. That pattern was jvm-only because the
 * `traitSpaceOf` helper lived in `JvmForgeIo`. This file lifts the helper into
 * commonMain so every target can register production workgroups, and pins the
 * model-facade ("modelmux") workgroup as a named, reusable registration.
 *
 * Provider neutrality: the workgroup advertises [Capability.Model] — the
 * *capability*, never a provider identity. Anthropic, OpenAI-compatible, Jules,
 * opencode and kilo facades all claim through the same trait; admission is by
 * capability × subnet only. Nothing here may branch on who the provider is.
 */

/**
 * Build a [TraitSpace] from a vararg of capabilities.
 *
 * The trait space is a lazy `α` projection over the captured capabilities —
 * `n j { i -> caps[i] }` — not a materialized collection, so matching walks
 * the series on demand.
 */
fun traitSpaceOf(vararg capabilities: Capability): TraitSpace {
    // Defensive copy: the vararg array is caller-visible, and the TraitSpace
    // outlives this call as a lazy projection over it.
    val caps: Array<Capability> = Array(capabilities.size) { i -> capabilities[i] }
    val series: Series<Capability> = caps.size j { i -> caps[i] }
    return TraitSpace { series }
}

/** Registry name of the canonical model-facade workgroup. */
const val MODEL_WORKGROUP_NAME: String = "modelmux-local"

/**
 * The model-facade workgroup: [Capability.Model] (category "modelmux") at
 * [Subnet.local]. The family wildcard [Capability.ModelAll] is included so a
 * request carrying any future `modelmux` leaf still lands here.
 */
fun modelWorkgroup(
    name: String = MODEL_WORKGROUP_NAME,
    scope: Subnet = Subnet.local,
): Workgroup = Workgroup(
    name = name,
    scope = scope,
    traits = traitSpaceOf(Capability.Model, Capability.ModelAll),
)

/**
 * Register [workgroup] on this fanout and promote the fanout to ACTIVE.
 *
 * Idempotent on the workgroup name (see [NuidFanoutElement.register]) and on
 * the lifecycle promotion, so repeated wiring is harmless. Returns the
 * registered workgroup so callers can hold its name for `slotOf`.
 */
suspend fun NuidFanoutElement.registerWorkgroup(workgroup: Workgroup): Workgroup {
    register(workgroup)
    activate()
    return workgroup
}

/** Register the canonical [modelWorkgroup] on this fanout. */
suspend fun NuidFanoutElement.registerModelWorkgroup(
    workgroup: Workgroup = modelWorkgroup(),
): Workgroup = registerWorkgroup(workgroup)
