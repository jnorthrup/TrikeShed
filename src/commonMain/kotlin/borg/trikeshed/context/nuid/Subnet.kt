package borg.trikeshed.context.nuid

sealed interface Subnet : Comparable<Subnet> {
    val level: Int

    override fun compareTo(other: Subnet): Int = this.level.compareTo(other.level)
    fun contains(other: Subnet): Boolean = this.level >= other.level

    data object Local : Subnet { override val level: Int = 1 }
    data object Lan : Subnet { override val level: Int = 2 }
    data object Mesh : Subnet { override val level: Int = 3 }
    data object Global : Subnet { override val level: Int = 4 }
}
