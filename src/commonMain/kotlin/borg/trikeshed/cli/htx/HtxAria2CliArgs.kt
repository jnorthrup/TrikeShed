package borg.trikeshed.cli.htx

data class Aria2Options(
    val dir: String? = null,
    val out: String? = null,
    val split: Int = 5,
    val maxConnectionPerServer: Int = 1,
    val minSplitSize: Long = 20 * 1024 * 1024,
    val maxConcurrentDownloads: Int = 5,
    val continueDownload: Boolean = false,
    val checkIntegrity: Boolean = false,
    val checksum: String? = null,
    val uris: List<String> = emptyList()
)

object HtxAria2CliArgs {
    val ALL_LONG_SWITCHES = listOf(
        "--dir", "--out", "--split", "--max-connection-per-server", "--min-split-size",
        "--max-concurrent-downloads", "--continue", "--check-integrity", "--checksum",
        "--all-proxy", "--all-proxy-passwd", "--all-proxy-user", "--allow-overwrite",
        "--allow-piece-length-change", "--always-resume", "--async-dns", "--async-dns-server",
        "--auto-file-renaming", "--auto-save-interval", "--bt-detach-seed-only", "--bt-enable-hook-after-hash-check",
        "--bt-enable-lpd", "--bt-exclude-tracker", "--bt-external-ip", "--bt-force-encryption",
        "--bt-hash-check-seed", "--bt-load-saved-metadata", "--bt-max-open-files", "--bt-max-peers",
        "--bt-metadata-only", "--bt-min-crypto-level", "--bt-prioritize-piece", "--bt-remove-unselected-file",
        "--bt-require-crypto", "--bt-save-metadata", "--bt-seed-unverified", "--bt-stop-timeout",
        "--bt-tracker", "--bt-tracker-connect-timeout", "--bt-tracker-interval", "--bt-tracker-timeout",
        "--ca-certificate", "--certificate", "--check-certificate", "--conditional-get",
        "--conf-path", "--console-log-level", "--content-disposition-default-utf8", "--continue",
        "--daemon", "--dht-entry-point", "--dht-entry-point6", "--dht-file-path",
        "--dht-file-path6", "--dht-listen-addr6", "--dht-listen-port", "--dht-message-timeout",
        "--disable-ipv6", "--disk-cache", "--download-result", "--dry-run",
        "--enable-async-dns6", "--enable-color", "--enable-dht", "--enable-dht6",
        "--enable-http-keep-alive", "--enable-http-pipelining", "--enable-mmap", "--enable-peer-exchange",
        "--enable-rpc", "--event-poll", "--file-allocation", "--force-save",
        "--ftp-pasv", "--ftp-proxy", "--ftp-proxy-passwd", "--ftp-proxy-user",
        "--ftp-reuse-connection", "--ftp-type", "--ftp-user", "--ftp-passwd",
        "--hash-check-only", "--header", "--help", "--http-accept-gzip",
        "--http-auth-challenge", "--http-no-cache", "--http-proxy", "--http-proxy-passwd",
        "--http-proxy-user", "--http-user", "--http-passwd", "--https-proxy",
        "--https-proxy-passwd", "--https-proxy-user", "--index-out", "--input-file",
        "--keep-unfinished-download-result", "--load-cookies", "--log", "--log-level",
        "--max-connection-per-server", "--max-concurrent-downloads", "--max-download-limit", "--max-download-result",
        "--max-file-not-found", "--max-mmap-limit", "--max-overall-download-limit", "--max-overall-upload-limit",
        "--max-resume-failure-tries", "--max-tries", "--max-upload-limit", "--metalink-base-uri",
        "--metalink-enable-unique-protocol", "--metalink-file", "--metalink-language", "--metalink-location",
        "--metalink-os", "--metalink-preferred-protocol", "--metalink-version", "--min-split-size",
        "--min-tls-version", "--multiple-use-cert", "--netrc-path", "--no-conf",
        "--no-file-allocation-limit", "--no-netrc", "--no-proxy", "--on-bt-download-complete",
        "--on-download-complete", "--on-download-error", "--on-download-pause", "--on-download-start",
        "--on-download-stop", "--optimize-concurrent-downloads", "--out", "--parameterized-uri",
        "--pause", "--pause-metadata", "--piece-length", "--proxy-method",
        "--quiet", "--realtime-chunk-checksum", "--referer", "--remote-time",
        "--remove-control-file", "--retry-wait", "--reuse-uri", "--rpc-allow-origin-all",
        "--rpc-certificate", "--rpc-listen-all", "--rpc-listen-port", "--rpc-max-request-size",
        "--rpc-passwd", "--rpc-private-key", "--rpc-save-upload-metadata", "--rpc-secret",
        "--rpc-secure", "--rpc-user", "--save-cookies", "--save-not-found",
        "--save-session", "--save-session-interval", "--seed-ratio", "--seed-time",
        "--server-stat-if", "--server-stat-of", "--server-stat-timeout", "--show-console-readout",
        "--show-files", "--split", "--stderr", "--stop",
        "--stop-with-process", "--stream-piece-selector", "--summary-interval", "--timeout",
        "--truncate-console-readout", "--uri-selector", "--use-head", "--user-agent"
    )

    fun parse(args: Array<String>): Aria2Options {
        var dir: String? = null
        var out: String? = null
        var split = 5
        var maxConnectionPerServer = 1
        var minSplitSize = 20 * 1024 * 1024L
        var maxConcurrentDownloads = 5
        var continueDownload = false
        var checkIntegrity = false
        var checksum: String? = null
        val uris = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]

            if (arg == "-h" || arg == "--help" || arg.startsWith("--help=")) {
                printHelp()
                throw UnsupportedOperationException("Help requested")
            }

            if (arg.startsWith("-")) {
                val key = arg.substringBefore("=")
                val value = if (arg.contains("=")) arg.substringAfter("=") else {
                    if (i + 1 < args.size && !args[i + 1].startsWith("-")) {
                        i++
                        args[i]
                    } else null
                }

                if (ALL_LONG_SWITCHES.contains(key)) {
                    when (key) {
                        "--dir" -> dir = value ?: throw IllegalArgumentException("Missing value for --dir")
                        "--out" -> out = value ?: throw IllegalArgumentException("Missing value for --out")
                        "--split" -> split = value?.toIntOrNull() ?: throw IllegalArgumentException("Invalid value for --split")
                        "--max-connection-per-server" -> maxConnectionPerServer = value?.toIntOrNull() ?: throw IllegalArgumentException("Invalid value for --max-connection-per-server")
                        "--min-split-size" -> minSplitSize = parseSize(value ?: throw IllegalArgumentException("Missing value for --min-split-size"))
                        "--max-concurrent-downloads" -> maxConcurrentDownloads = value?.toIntOrNull() ?: throw IllegalArgumentException("Invalid value for --max-concurrent-downloads")
                        "--continue" -> continueDownload = true
                        "--check-integrity" -> checkIntegrity = true
                        "--checksum" -> checksum = value ?: throw IllegalArgumentException("Missing value for --checksum")
                        else -> throw UnsupportedOperationException("Unsupported option: $key")
                    }
                } else {
                     throw UnsupportedOperationException("Unsupported option: $key")
                }
            } else {
                uris.add(arg)
            }
            i++
        }

        return Aria2Options(
            dir = dir,
            out = out,
            split = split,
            maxConnectionPerServer = maxConnectionPerServer,
            minSplitSize = minSplitSize,
            maxConcurrentDownloads = maxConcurrentDownloads,
            continueDownload = continueDownload,
            checkIntegrity = checkIntegrity,
            checksum = checksum,
            uris = uris
        )
    }

    private fun parseSize(sizeStr: String): Long {
        val multiplier = when {
            sizeStr.endsWith("K", ignoreCase = true) -> 1024L
            sizeStr.endsWith("M", ignoreCase = true) -> 1024L * 1024L
            sizeStr.endsWith("G", ignoreCase = true) -> 1024L * 1024L * 1024L
            else -> 1L
        }
        val numStr = sizeStr.takeWhile { it.isDigit() }
        return (numStr.toLongOrNull() ?: throw IllegalArgumentException("Invalid size: $sizeStr")) * multiplier
    }

    private fun printHelp() {
        println("TrikeShed HTX Aria2 CLI")
        println("Supported core switches:")
        println("  --dir, --out, --split, --max-connection-per-server, --min-split-size, --max-concurrent-downloads, --continue, --check-integrity, --checksum")
        println("Use 'aria2c --help=#all' for the full list of unsupported switches that will explicitly fail.")
    }
}
