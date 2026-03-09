package borg.trikeshed.common

import borg.trikeshed.ccek.KeyedService
import kotlin.coroutines.CoroutineContext

val homedir: String by lazy { System.homedir }
val homedirGet: String get() = System.homedir

/** emulates shell command*/
 expect fun mktemp():String

 expect fun rm (path:String):Boolean
 /* mkdir -p*/
 expect fun mkdir (path:String):Boolean

/** CCEK keyed service wrapping the home directory path. */
data class HomeDirService(val path: String) : KeyedService {
    companion object Key : CoroutineContext.Key<HomeDirService>
    override val key: CoroutineContext.Key<*> get() = Key
}
