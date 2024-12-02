package borg.trikeshed.io

actual val homedirGet: String
    get() = System.getProperty("user.home")!!
