package borg.trikeshed.lcnc

import borg.trikeshed.lib.*

/** Permissive scan geometry; fault zero does not certify document validity. */
object LlvmConfixContract {
    const val TYPE = "vm.llvm.confix"
    fun contract() = LcncPortContract(TYPE, "LLVM Confix scanner", _l["data?"],
        _l["ok", "format", "rows", "rowCount", "fault", "sourceCid", "runtime"],
        inputKinds = _m["data" j "text"],
        outputKinds = _m["ok" j "Boolean", "format" j "text", "rows" j "json", "rowCount" j "num", "fault" j "json", "sourceCid" j "id", "runtime" j "json"],
        isEffect = true, params = _m[
            "format" j LcncPortContract.LcncParamSpec(v = "JSON", opts = _l["JSON", "CBOR", "YAML"]),
            "data" j LcncPortContract.LcncParamSpec(ta = true, ph = "UTF-8 text; CBOR uses canonical base64")])
}
