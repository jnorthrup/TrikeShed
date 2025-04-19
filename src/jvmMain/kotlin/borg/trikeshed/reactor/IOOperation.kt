package borg.trikeshed.reactor

actual class IOOperation private constructor(actual val value: Int) {
    actual companion object {
        actual val Read = IOOperation(1)
        actual val Write = IOOperation(4) 
        actual val Accept = IOOperation(16)
        actual val Connect = IOOperation(8)
    }
}
