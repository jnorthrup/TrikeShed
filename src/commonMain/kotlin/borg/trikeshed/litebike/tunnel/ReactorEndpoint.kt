package borg.trikeshed.litebike.tunnel

/**
 * ReactorEndpoint — `ReactorAction`/`ReactorResult` request/response algebra.
 * Used by browser to ask a native/node peer to open a tunnel.
 */
interface ReactorEndpoint<Action, Result> {
    suspend fun invoke(action: Action): Result
}

/**
 * Specific endpoint for Tunnel actions.
 */
interface TunnelEndpoint : ReactorEndpoint<TunnelRequest, TunnelResponse>
