package borg.trikeshed.context

import java.util.ServiceLoader

fun loadUserspaceNioSpi(): UserspaceNioSpi =
    ServiceLoader.load(UserspaceNioSpi::class.java).firstOrNull()
        ?: error("No UserspaceNioSpi provider registered in META-INF/services")

