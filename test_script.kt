import borg.trikeshed.userspace.containment.StigmergicProtocolDecoder
import borg.trikeshed.userspace.containment.PatchData

fun main() {
    val decoder = StigmergicProtocolDecoder()
    val p1 = PatchData("swarm_1", "swarm_1", "test")
    println(decoder.decode(listOf(p1)))
}
