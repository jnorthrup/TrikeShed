package borg.trikeshed.isam.meta

enum class IOMemento(override val networkSize: Int? = null) : TypeMemento {
    IoBoolean(1),
    IoByte(1),
    IoShort(2),
    IoInt(4),
    IoLong(8),
    IoFloat(4),
    IoDouble(8),
    IoLocalDate(8),
    /**
     * 12 bytes of storage, first epoch seconds Long , then nanos Int
     */
    IoInstant(12),
    IoString,
    IoNothing;}

