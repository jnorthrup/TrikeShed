package gk.kademlia.codec

interface Codec<Evt, Serde> {
    fun send(event: Evt): Serde?
    fun recv(ser: Serde): Evt?
}