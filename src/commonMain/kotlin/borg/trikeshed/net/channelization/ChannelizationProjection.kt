package borg.trikeshed.net.channelization

/**
 * Minimal projection of a channelization plan onto the session/block core.
 *
 * Represents the smallest generic session shape that can be instantiated
 * from a [ChannelizationPlan]. Transport-agnostic and protocol-neutral.
 */
data class ChannelizationProjection(
    val plan: ChannelizationPlan,
    val sessionShape: SessionShape,
)

/**
 * Generic session shape descriptor.
 *
 * Describes the minimal structural properties needed to instantiate
 * a channel session without binding to any specific backend or protocol.
 */
data class SessionShape(
    val sessionId: ChannelSessionId,
    val semantics: ChannelSemantics,
    val path: ChannelizationPath,
)

/**
 * Project a [ChannelizationPlan] to a generic session shape.
 *
 * Creates the minimal projection needed to express a session in terms
 * of the session/block core types. Does not allocate resources or bind
 * to any transport backend.
 */
fun ChannelizationPlan.projectToSessionShape(): ChannelizationProjection {
    val sessionShape = SessionShape(
        sessionId = ChannelSessionId("projected-${protocol.name}"),
        semantics = semantics,
        path = path,
    )
    return ChannelizationProjection(
        plan = this,
        sessionShape = sessionShape,
    )
}

/**
 * Select channelization and project to session shape.
 *
 * Convenience function that combines selection and projection for
 * callers that need the projected session shape directly.
 */
suspend fun selectAndProjectChannelization(
    request: ChannelizationRequest,
): ChannelizationProjection {
    val plan = selectChannelization(request)
    return plan.projectToSessionShape()
}
