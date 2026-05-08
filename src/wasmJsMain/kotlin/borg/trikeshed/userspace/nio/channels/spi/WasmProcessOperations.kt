package borg.trikeshed.userspace.nio.channels.spi

class WasmProcessOperations : ProcessOperations {

    override fun exec(command: String, vararg args: String): ExecResult =
        ExecResult(-1, "", "exec not available on WASM")
}
