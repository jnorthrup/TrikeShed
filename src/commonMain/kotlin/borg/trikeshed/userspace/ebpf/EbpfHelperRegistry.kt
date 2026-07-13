package borg.trikeshed.userspace.ebpf

class EbpfHelperRegistry {
    private val helpers = mutableMapOf<Int, (LongArray, ByteArray) -> Long>()

    // BPF standard helper IDs
    val BPF_FUNC_map_lookup_elem = 1
    val BPF_FUNC_map_update_elem = 2
    val BPF_FUNC_map_delete_elem = 3

    // Example: R1=map_ptr, R2=key_ptr
    // In our userspace mock, the interpreter resolves pointers as indices or raw byte offsets

    fun registerHelper(id: Int, func: (LongArray, ByteArray) -> Long) {
        helpers[id] = func
    }

    fun callHelper(id: Int, registers: LongArray, context: ByteArray): Long {
        val func = helpers[id] ?: return 0L // Silent fail for unregistered helpers in this basic engine
        return func(registers, context)
    }
}
