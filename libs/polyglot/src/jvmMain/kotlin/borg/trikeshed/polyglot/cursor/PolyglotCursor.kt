package borg.trikeshed.polyglot.cursor

/**
 * PointcutFacet — taxonomic classification for pointcut interception sites.
 * Replaces org.xvm.cursor.PointcutFacet
 */
sealed class PointcutFacet {
    object MethodEntry : PointcutFacet()
    object MethodExit : PointcutFacet()
    object FieldRead : PointcutFacet()
    object FieldWrite : PointcutFacet()
    object Allocation : PointcutFacet()
    object Unfaceted : PointcutFacet()
}