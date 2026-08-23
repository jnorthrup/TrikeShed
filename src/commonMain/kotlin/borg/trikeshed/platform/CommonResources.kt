package borg.trikeshed.platform

import borg.trikeshed.forge.generated.ForgeResourceBundle

/**
 * Common resources — `src/commonMain/resources/…` readable on every target through one door.
 *
 * Two layers, first hit wins:
 * 1. [ForgeResourceBundle] — an allowlist baked into Kotlin at build time by `generateForgeAssets`
 *    (schemas, OpenAPI specs). Works in a jar, in a browser, in a Worker, in native — no filesystem.
 * 2. [PlatformHost.resourceSource] — the host's own reader (JVM classloader / source tree, POSIX
 *    `fopen` of the source tree). Targets without one bind [ResourceSource.NONE], whose every call
 *    is a [discontinued] chokepoint, so a missing resource is reported, never silently empty.
 */
interface ResourceSource {
    fun bytes(path: String): ByteArray? = discontinued("resources.bytes")

    companion object {
        val NONE: ResourceSource = object : ResourceSource {}
    }
}

fun ResourceSource.text(path: String): String? = bytes(path)?.decodeToString()

/** Normalises `classpath:/confix/x.json`, `/confix/x.json` and `confix/x.json` to the bundle key. */
fun resourceKey(path: String): String = path.removePrefix("classpath:").trimStart('/')

val PlatformHost.resourceSource: ResourceSource get() = (resources as? ResourceSource) ?: ResourceSource.NONE

object CommonResources : ResourceSource {
    /** Keys the build baked in; a convenient inventory for the host view and tests. */
    val baked: Set<String> get() = ForgeResourceBundle.map.keys

    override fun bytes(path: String): ByteArray? {
        val key = resourceKey(path)
        ForgeResourceBundle.map[key]?.let { return it }
        val host = runCatching { PlatformHost.default.resourceSource }.getOrElse { ResourceSource.NONE }
        return if (host === ResourceSource.NONE) {
            Discontinued.declare("resources.bytes")
            null
        } else runCatching { host.bytes(key) }.getOrNull()
    }
}
