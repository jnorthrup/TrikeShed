package borg.trikeshed.lcnc.rollup

import borg.trikeshed.lcnc.isam.LcncDatabase

data class RollupContext(
    val source: LcncDatabase,
    val propertyId: String,
    val config: RollupConfig = RollupConfig(),
)
