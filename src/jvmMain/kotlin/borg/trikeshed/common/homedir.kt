package borg.trikeshed.common

 actual val homedirGet: String
    get() = System.getProperty("user.home")!!
