@file:Suppress("KDocMissingDocumentation", "EnumEntryName")
package borg.trikeshed.kademlia.agent
import borg.trikeshed.kademlia.agent.EventTypes.EventKey.*
enum class EventTypes(
    vararg keys: EventKey,
) {
    PING(recent_nuids, lagging_nuids, my_uid, my_route),
    PONG(nuids, routes, pubkeys, suggest),
    JOIN(proposed, former, mypubkey),
    LAGD(nuid),
    BUBY(address, addrpattern, evictpubkey),
    ;
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
