package borg.trikeshed.lib

import borg.trikeshed.userspace.nio.platform.spi.SystemOperations

/**
 * @deprecated Use [SystemOperations] via CCEK: `coroutineContext[SystemOperations.Key]`
 */
typealias System = SystemOperations
