package borg.trikeshed.forge

/**
 * Multiplatform UUID generator.
 * Uses java.util.UUID on JVM, random UUID on JS/Native.
 */
expect fun UuidGenerator.generate(): String