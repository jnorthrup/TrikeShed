@file:Suppress("KDocMissingDocumentation", "EnumEntryName")

package gk.kademlia.agent

import gk.kademlia.agent.EventTypes.EventKey.*


/**
 * mostly placeholders here
 */
enum class EventTypes(
    /** proposed keys in the FSM ReadChunk/WriteChunk
     */
    vararg keys: EventKey,
) {
    /** sends a ping to dest expression
     */
    PING(recent_nuids, lagging_nuids, my_uid, my_route),

    /** sends pong to Address
     */
    PONG(nuids, routes, pubkeys, suggest),

    /** sends random address proposal or pubkey modulo to all known Routes in
     * cache with random ms intervals
     *
     * todo: include pubkey
     *
     * 1. random timer starts to accept only one closest response favoring even-higher or odd-lower
     * 1. accepts first concurring pair of responses and ends timer. ( voter_count=strength+1)
     * 1. timesout and uses the  proposal NUID as strength=1 on empty dance floor. (assumes netsplit)
     * 1. sends a ping with new address
     */
    JOIN(proposed, former, mypubkey),

    /** weakest possible NUID record removal
     */
    LAGD(nuid),

    /** network eviction
     *
     * creates blocking expressions, against keys and addresses and netmask expressions.
     *
     *  BuhBye now!
     */
    BUBY(address, addrpattern, evictpubkey),
    ;

    /**
     * mostly placeholders
     */
    enum class EventKey {
        recent_nuids,
        lagging_nuids,
        my_uid,
        my_route,
        nuids,
        pubkeys,
        suggest,
        proposed,
        former,
        mypubkey,
        nuid,
        routes,
        address,
        addrpattern,
        evictpubkey,
        ;
    }
}