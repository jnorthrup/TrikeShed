package borg.trikeshed.couch.relaxfactory

@Target(AnnotationTarget.FUNCTION)
annotation class View(val map: String, val reduce: String = "")

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Key

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class StartKey

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class EndKey

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Limit

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Skip

@Target(AnnotationTarget.FUNCTION)
annotation class Descending(val value: Boolean)

@Target(AnnotationTarget.FUNCTION)
annotation class Group(val value: Boolean)

@Target(AnnotationTarget.FUNCTION)
annotation class GroupLevel(val value: Int)

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Keys

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class StartKeyDocId

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class EndKeyDocId
