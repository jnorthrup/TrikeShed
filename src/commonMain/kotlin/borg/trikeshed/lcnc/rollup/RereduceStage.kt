package borg.trikeshed.lcnc.rollup

interface RereduceStage {
    fun apply(carrier: Any, ctx: RollupContext): Any
}

class DefaultRereduceStage : RereduceStage {
    override fun apply(carrier: Any, ctx: RollupContext): Any {
        return carrier
    }
}
