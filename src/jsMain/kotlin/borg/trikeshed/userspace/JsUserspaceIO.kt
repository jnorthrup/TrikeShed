package borg.trikeshed.userspace

actual class UserspaceBuffer(private val backing: ByteArray) {
    private val common = CommonUserspaceBuffer(backing)

    actual val size: Int get() = common.size
    actual fun get(index: Int): Byte = common.get(index)
    actual fun put(index: Int, value: Byte) {
        common.put(index, value)
    }
}

actual class UserspaceFD(actual val id: Int) {
    private val common = CommonUserspaceFD(id)

    actual fun isInvalid(): Boolean = common.isInvalid()
}

private object JsUserspaceSPI : UserspaceSPI {
    private val allocator = CommonUserspaceFdAllocator()

    override fun createRing(entries: Int): UserspaceRing = CommonUserspaceRing()

    override fun openFile(path: String, readOnly: Boolean): UserspaceFD = UserspaceFD(allocator.next())

    override fun createSocket(domain: Int, type: Int, protocol: Int): UserspaceFD = UserspaceFD(allocator.next())

    override fun wrapBuffer(byteArray: ByteArray): UserspaceBuffer = UserspaceBuffer(byteArray)
}

actual val userspaceSPI: UserspaceSPI = JsUserspaceSPI
