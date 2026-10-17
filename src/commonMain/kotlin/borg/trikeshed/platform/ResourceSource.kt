package borg.trikeshed.platform

/** A host's resource reader. Targets without one bind [NONE], whose every call is a [discontinued] chokepoint. */
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
