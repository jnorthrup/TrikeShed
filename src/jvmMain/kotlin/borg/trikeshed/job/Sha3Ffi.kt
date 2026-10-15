package borg.trikeshed.job

import java.lang.foreign.*
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandle

/**
 * SHA-3 through OpenSSL 3 libcrypto: EVP_Digest over an EVP_MD fetched once. Critical downcalls pass the
 * input and digest arrays in place. Library: -Dtrikeshed.libcrypto, else libcrypto.so.3, else Homebrew's.
 */
object Sha3Ffi {
    val linker: Linker = Linker.nativeLinker()
    val lookup: SymbolLookup = listOfNotNull(System.getProperty("trikeshed.libcrypto"), "libcrypto.so.3",
        "/opt/homebrew/opt/openssl@3/lib/libcrypto.3.dylib", "/usr/local/opt/openssl@3/lib/libcrypto.3.dylib")
        .firstNotNullOf { runCatching { SymbolLookup.libraryLookup(it, Arena.global()) }.getOrNull() }

    fun handle(name: String, fd: FunctionDescriptor, vararg options: Linker.Option): MethodHandle =
        linker.downcallHandle(lookup.find(name).orElseThrow { UnsatisfiedLinkError(name) }, fd, *options)

    /** int EVP_Digest(const void *data, size_t count, unsigned char *md, unsigned int *size, const EVP_MD *type, ENGINE *impl) */
    val evpDigest: MethodHandle = handle("EVP_Digest",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS), Linker.Option.critical(true))

    /** EVP_MD *EVP_MD_fetch(OSSL_LIB_CTX *ctx, const char *algorithm, const char *properties) */
    val evpMdFetch: MethodHandle = handle("EVP_MD_fetch", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS))

    fun fetch(name: String): MemorySegment {
        val md = evpMdFetch.invokeExact(MemorySegment.NULL, Arena.global().allocateFrom(name), MemorySegment.NULL) as MemorySegment
        check(md.address() != 0L) { "libcrypto has no $name" }
        return md
    }

    val sha3_224: MemorySegment = fetch("SHA3-224")
    val sha3_256: MemorySegment = fetch("SHA3-256")
    val sha3_384: MemorySegment = fetch("SHA3-384")
    val sha3_512: MemorySegment = fetch("SHA3-512")

    /** SHA-3 of [size] digest bytes (28, 32, 48 or 64) of [count] bytes of [input] from [offset]. */
    fun digest(input: ByteArray, offset: Int = 0, count: Int = input.size - offset, size: Int = 32): ByteArray {
        val md = when (size) { 28 -> sha3_224; 32 -> sha3_256; 48 -> sha3_384; 64 -> sha3_512; else -> error("SHA-3 digest size $size") }
        val out = ByteArray(size)
        val rc = evpDigest.invokeExact(MemorySegment.ofArray(input).asSlice(offset.toLong(), count.toLong()), count.toLong(),
            MemorySegment.ofArray(out), MemorySegment.NULL, md, MemorySegment.NULL) as Int
        check(rc == 1) { "EVP_Digest failed" }
        return out
    }
}
