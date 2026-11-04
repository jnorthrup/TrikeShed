package borg.trikeshed.lcnc

import borg.trikeshed.lib.*

/** Operator-pinned binary; the graph supplies only the two scalar arguments. */
object LlvmScalarContract {
    const val TYPE = "vm.llvm.scalar"
    fun contract() = LcncPortContract(TYPE, "LLVM scalar kernel", _l["left", "right"], _l["value", "runtime"],
        inputKinds = _m["left" j "num", "right" j "num"],
        outputKinds = _m["value" j "num", "runtime" j "json"], isEffect = true,
        params = _m["left" j LcncPortContract.LcncParamSpec(ph = "Signed 32-bit integer"),
            "right" j LcncPortContract.LcncParamSpec(ph = "Signed 32-bit integer")])
}
