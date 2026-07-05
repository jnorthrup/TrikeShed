package borg.trikeshed.uring

import borg.trikeshed.context.LiburingFacadeSpi

class LiburingFacadeProvider : LiburingFacadeSpi {
    override suspend fun submitRead(fd: Int, buf: ByteArray): Int = 0

    override suspend fun submitWrite(fd: Int, buf: ByteArray): Int = 0

    override suspend fun poll(): List<Any> = emptyList()
}
