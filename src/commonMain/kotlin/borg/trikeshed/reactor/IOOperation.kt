package borg.trikeshed.reactor

expect class IOOperation {
    val value: Int
    
    companion object {
        val Read: IOOperation
        val Write: IOOperation
        val Accept: IOOperation
        val Connect: IOOperation
    }
}
