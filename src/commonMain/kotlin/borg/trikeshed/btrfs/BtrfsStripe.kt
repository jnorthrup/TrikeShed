package borg.trikeshed.btrfs

data class BtrfsStripe(val devid: ULong, val offset: ULong, val uuid: List<UByte> = List(16) { 0u })
