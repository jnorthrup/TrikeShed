package borg.trikeshed.forge

import java.util.UUID

actual fun UuidGenerator.generate(): String = UUID.randomUUID().toString()