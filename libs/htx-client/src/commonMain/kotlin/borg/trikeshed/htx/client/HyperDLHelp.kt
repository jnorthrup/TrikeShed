package borg.trikeshed.htx.client

/**
 * HyperDL CLI help text.
 * Command-line interface compatible with aria2c options.
 * Keeps the minimal set of options the codebase relies on (see HyperDLSwitches).
 */
object HyperDLHelp {
    fun helpText(): CharSequence = """
hyperdl — multi-protocol & multi-source download utility (TrikeShed native)

Usage: hyperdl [OPTIONS] [URI]...

General Options:
  -h, --help                      Show help and exit
  -Z, --force-serialization       Force serialization (pipeline-specific; always on in this project)
  -c, --continue                  Resume downloads (continue)
  --save-not-found=<true|false>   Save when 404 (default: false)
  -x, --max-connection-per-server=<N>  Max connections per server (default: 15)
  -j, --max-concurrent-downloads=<N>  Max concurrent downloads (default: 15)
  -s, --split=<N>                 Number of connections per download (split) (default: 15)
  -d, --dir=<DIR>                 Directory to store downloads
  --header="Key: Value"           Add custom header

RPC interface compatible with aria2 JSON-RPC protocol.
""".trimIndent()

    fun printHelp() {
        println(helpText())
    }
}
