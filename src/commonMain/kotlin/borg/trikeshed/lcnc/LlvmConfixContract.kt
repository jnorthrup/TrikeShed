package borg.trikeshed.lcnc

import borg.trikeshed.lib.*

/** Permissive scan geometry; fault zero does not certify document validity. */
object LlvmConfixContract {
    const val TYPE = "vm.llvm.confix"
    fun contract() = LcncPortContract(TYPE, "LLVM Confix scanner", _l["data?"],
        _l["ok", "rows", "rowCount", "fault", "sourceCid", "runtime"],
        inputKinds = _m["data" j "text"],
        outputKinds = _m["ok" j "bool", "rows" j "json", "rowCount" j "num", "fault" j "json", "sourceCid" j "text", "runtime" j "json"],
        isEffect = true, params = _m[
            "format" j LcncPortContract.LcncParamSpec(ph = "JSON, CBOR or YAML"),
            "data" j LcncPortContract.LcncParamSpec(ph = "UTF-8 text; CBOR uses canonical base64")])
}
