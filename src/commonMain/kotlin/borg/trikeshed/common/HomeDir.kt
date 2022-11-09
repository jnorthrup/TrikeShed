package borg.trikeshed.common


 val homedir: String by lazy { homedirGet  }

/** emulates shell command*/
 expect fun mktemp():String

 expect fun rm (path:String):Boolean
 /* mkdir -p*/
 expect fun mkdir (path:String):Boolean
