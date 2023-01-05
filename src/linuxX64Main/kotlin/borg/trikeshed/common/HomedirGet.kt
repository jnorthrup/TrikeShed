package borg.trikeshed.common

import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * get homedir
 */ actual val homedirGet: String by lazy {
    val home = getenv("HOME")
    if (home != null) {
        return@lazy home.toKString()
    }
    val userprofile = getenv("USERPROFILE")
    if (userprofile != null) {
        return@lazy userprofile.toKString()
    }
    val homedrive = getenv("HOMEDRIVE")
    val homepath = getenv("HOMEPATH")
    if (homedrive != null && homepath != null) {
        return@lazy homedrive.toKString() + homepath.toKString()
    }
    throw Error("Failed to determine home directory")
}