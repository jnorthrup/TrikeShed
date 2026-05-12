package borg.trikeshed.userspace.nio

open class IOException(message: CharSequence? = null, cause: Throwable? = null) : Exception(message?.toString(), cause)
