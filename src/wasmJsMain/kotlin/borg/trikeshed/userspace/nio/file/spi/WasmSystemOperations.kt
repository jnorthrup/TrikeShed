package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.userspace.nio.platform.spi.SystemOperations

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(name) => (typeof process !== 'undefined' && process.env) ? (process.env[name] || null) : null")
private external fun getEnvJs(name: String): String?

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => { if (typeof process !== 'undefined' && process.env) { return process.env.HOME || process.env.USERPROFILE || '/' } return '/' }")
private external fun getHomeDirJs(): String

class WasmSystemOperations : SystemOperations {

    override fun getenv(name: String, defaultVal: String?) = getEnvJs(name) ?: defaultVal
    override fun getProperty(name: String, defaultVal: String?) = defaultVal
    override val homedir: String get() = getHomeDirJs()
}
