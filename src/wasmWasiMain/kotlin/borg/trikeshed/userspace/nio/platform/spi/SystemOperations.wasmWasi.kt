package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.wasi.Wasi

/** random_get fills at most 64 KiB per call. */
actual fun platformGetRandom(bytes: ByteArray) = Wasi.random(bytes)

/** WASI environment and arguments from environ_get / args_get. */
class WasiSystemOperations : SystemOperations {
    override fun getenv(name: String, defaultVal: String?): String? = Wasi.environment[name] ?: defaultVal
    override fun getProperty(name: String, defaultVal: String?): String? = defaultVal
    override val homedir: String get() = Wasi.environment["HOME"] ?: Wasi.cwd
    override fun args_os(): List<ByteArray> = Wasi.arguments
}

actual fun loadDefaultSystemOperations(): SystemOperations = WasiSystemOperations()
