package borg.trikeshed.common.isam.meta

enum class IOMemento(override val networkSize: Int? = null) : TypeMemento {
    IoBoolean(1),
    IoByte(1),
    IoInt(4),
    IoLong(8),
    IoFloat(4),
    IoDouble(8),
    IoString,
    IoLocalDate(8),

    /**
     * 12 bytes of storage, first epoch seconds Long , then nanos Int
     */
    IoInstant(12),
    IoNothing
    ;
}
