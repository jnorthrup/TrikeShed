package borg.trikeshed.uring

/** The shape of a real io_uring CQE — what the Linux impl must produce. */
data class UringCqe(
    val userData: Long,
    val res: Int,
    val flags: Int,
)

/** Stub SQE for documenting the prep shape. */
data class UringSqe(
    val opcode: Int,
    val fd: Int,
    val addr: Long,
    val len: Int,
    val off: Long,
    val userData: Long,
) {
    companion object {
        const val IORING_OP_READ = 22
        const val IORING_OP_WRITE = 23
        const val IORING_OP_ACCEPT = 13
        const val IORING_OP_CONNECT = 14
        const val IORING_OP_CLOSE = 19
    }
}
