import borg.trikeshed.dag.BlackboardFabrics

fun main() {
    try {
        val fabric = BlackboardFabrics.create()
        println("Success")
    } catch (e: NotImplementedError) {
        println("Caught NotImplementedError as expected")
    }
}
