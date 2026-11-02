@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.common.homedirGet
import kotlinx.cinterop.*
import platform.posix._get_osfhandle
import platform.posix.memset
import platform.windows.BCRYPT_USE_SYSTEM_PREFERRED_RNG
import platform.windows.BCryptGenRandom
import platform.windows.ERROR_IO_PENDING
import platform.windows.ERROR_LOCK_VIOLATION
import platform.windows.GetLastError
import platform.windows.LOCKFILE_EXCLUSIVE_LOCK
import platform.windows.LOCKFILE_FAIL_IMMEDIATELY
import platform.windows.LockFileEx
import platform.windows.OVERLAPPED
import platform.windows.UnlockFileEx

/**
 * flock(2) on a C runtime descriptor as LockFileEx over the whole file: LOCK_SH 1, LOCK_EX 2, LOCK_NB 4, LOCK_UN 8
 * (Linux values). A held lock under LOCK_NB answers -11 (EWOULDBLOCK); a descriptor with no handle -9.
 */
actual fun platformFlock(fd: Int, operation: Int): Int = memScoped {
    val raw = _get_osfhandle(fd)
    if (raw == -1L) return@memScoped -9
    val handle = raw.toCPointer<CPointed>()
    val overlapped = alloc<OVERLAPPED>()
    memset(overlapped.ptr, 0, sizeOf<OVERLAPPED>().convert())
    if (operation and 8 != 0)
        return@memScoped if (UnlockFileEx(handle, 0u, UInt.MAX_VALUE, UInt.MAX_VALUE, overlapped.ptr) != 0) 0 else -22
    var flags = 0
    if (operation and 2 != 0) flags = flags or LOCKFILE_EXCLUSIVE_LOCK
    if (operation and 4 != 0) flags = flags or LOCKFILE_FAIL_IMMEDIATELY
    if (LockFileEx(handle, flags.convert(), 0u, UInt.MAX_VALUE, UInt.MAX_VALUE, overlapped.ptr) != 0) 0
    else when (GetLastError().toInt()) {
        ERROR_LOCK_VIOLATION, ERROR_IO_PENDING -> -11
        else -> -5
    }
}

/** BCryptGenRandom with the system RNG (bcrypt is in the windows klib's link set). */
actual fun platformGetRandom(bytes: ByteArray) {
    if (bytes.isEmpty()) return
    val status = bytes.usePinned {
        BCryptGenRandom(null, it.addressOf(0).reinterpret(), bytes.size.convert(), BCRYPT_USE_SYSTEM_PREFERRED_RNG.convert())
    }
    check(status == 0) { "BCryptGenRandom failed: $status" }
}

/** Windows has no user id; a SID is not a uid. */
actual fun platformGeteuid(): UInt = TODO("geteuid(2) has no Windows equivalent")

/** Environment through the C runtime's getenv; no system properties on Windows. */
class MingwSystemOperations : SystemOperations {
    override fun getenv(name: String, defaultVal: String?): String? = platform.posix.getenv(name)?.toKString() ?: defaultVal
    override fun getProperty(name: String, defaultVal: String?): String? = defaultVal
    override val homedir: String get() = homedirGet
}

actual fun loadDefaultSystemOperations(): SystemOperations = MingwSystemOperations()
