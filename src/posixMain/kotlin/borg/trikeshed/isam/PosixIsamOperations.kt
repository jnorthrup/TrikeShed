package borg.trikeshed.isam

/** Source compatibility: select IsamReadMode.MMAP explicitly for mapped reads. */
typealias PosixIsamOperations = UringIsamOperations
typealias PosixIsamDataReader = UringIsamDataReader
