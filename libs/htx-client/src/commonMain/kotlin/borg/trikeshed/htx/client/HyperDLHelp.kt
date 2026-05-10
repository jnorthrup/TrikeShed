package borg.trikeshed.htx.client

/**
 * A small aria2c -h emulator (approximate) used for TDD and developer tooling.
 * Keeps the minimal set of options the codebase relies on (see Aria2Switches).
 */
object HyperDLHelp {
    fun helpText(): String = """
aria2c — lightweight multi-protocol & multi-source command-line download utility (emulation)

Usage: aria2c [OPTIONS] [URI]...

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
""".trimIndent()

    fun printHelp() {
        println(helpText())
    }
}
