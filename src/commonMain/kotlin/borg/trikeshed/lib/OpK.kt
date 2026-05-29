package borg.trikeshed.lib

/**
 * OpK — unified GADT key root.
 *
 * All faceted row discriminants descend from OpK<R> where R is the result type.
 * TextK, ColK, ManifoldK, LifeK are sibling sealed families.
 * New families nest as new OpK subclasses — they don't widen existing sealed hierarchies.
 */
abstract class OpK<out R>
