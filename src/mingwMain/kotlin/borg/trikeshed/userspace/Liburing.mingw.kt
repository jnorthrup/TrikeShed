package borg.trikeshed.userspace

/** Windows has no kernel ring; the facade session runs over the emulated backend (UserspaceIO.mingw.kt). */
internal actual object LiburingImpl : LiburingFacade by LiburingSession()
