package borg.trikeshed.forge.gateway

import java.io.File

internal actual fun readEnvFile(path: String): List<String> =
    File(path).readLines().filter { it.isNotBlank() && !it.trim().startsWith("#") }
