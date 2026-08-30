package borg.trikeshed.agent

/**
 * One external agent session as the board and the analyzers see it.
 *
 * Formerly `JulesRestClient.SessionInfo`, which made every consumer import a Jules API client to
 * name a five-field record. Jules is externalized now and that client is gone, but the shape is
 * not Jules-specific and never was: an id, a state, a title, how many bytes of patch it produced,
 * where it came from, when it last moved. Anything that runs agent sessions reports this.
 *
 * It lives in its own neutral package so the blackboard adapter and the collusion detector can
 * share it without either depending on a vendor integration.
 */
data class SessionInfo(
    val id: String,
    val state: String,
    val title: String,
    val patchBytes: Long,
    val source: String = "",
    val updateTime: String = "",
)

/**
 * One entry in an agent session's activity stream, as the board renders it.
 *
 * Formerly `JulesRestClient.ActivityInfo`. Same reasoning as [SessionInfo]: the shape describes any
 * agent conversation — an ordinal, who spoke, what kind of turn, how much patch it carried, and the
 * text — not a vendor's wire format.
 */
data class ActivityInfo(
    val id: String,
    val seq: Int,
    val createTime: String,
    val originator: String,
    /** agentMessaged | userMessaged | planGenerated | progressUpdated | artifacts */
    val kind: String,
    /** unidiff bytes carried by this activity, 0 if none */
    val patchBytes: Long,
    /** first 140 chars of the message body, if any */
    val excerpt: String,
    /** full message body */
    val message: String,
)
