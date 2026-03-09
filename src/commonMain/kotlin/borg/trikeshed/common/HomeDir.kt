package borg.trikeshed.common

val homedir: String by lazy { System.homedir }
val homedirGet: String get() = System.homedir

/** emulates shell command*/
 expect fun mktemp():String

 expect fun rm (path:String):Boolean
 /* mkdir -p*/
 expect fun mkdir (path:String):Boolean
