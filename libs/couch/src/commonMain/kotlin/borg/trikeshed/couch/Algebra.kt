package borg.trikeshed.couch

// Thin facade to re-export the kernel algebra from borg.trikeshed.lib
// Keeps libs/couch as the documented owner while avoiding moving original sources.

typealias Join<A, B> = borg.trikeshed.lib.Join<A, B>
typealias Twin<T> = borg.trikeshed.lib.Twin<T>
typealias Series<T> = borg.trikeshed.lib.Series<T>
typealias Series2<A, B> = borg.trikeshed.lib.Series2<A, B>

