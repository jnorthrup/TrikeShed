package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.lib.*

/** Environment supplied to a child process; host variables are never inherited implicitly. */
object ProcessEnvironment {
    private val fixed = _m["TERM" j "xterm-256color", "LANG" j "C.UTF-8"]
    private val toxic = _l[
        "KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "AUTH",
        "PRIVATE", "APIKEY", "ACCESS", "SESSION", "COOKIE", "CERT",
        "LD_PRELOAD", "LD_LIBRARY_PATH", "LD_AUDIT", "LD_PROFILE", "GLIBC_TUNABLES",
        "DYLD_", "PYTHONPATH", "PYTHONHOME", "NODE_OPTIONS",
        "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "BASH_ENV", "BASH_FUNC",
        "PERL5OPT", "PERL5LIB", "RUBYOPT", "RUBYLIB", "PROMPT_COMMAND",
        "PROXY", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
        "TLS", "SSL", "SOCKS", "CURL_CA_BUNDLE", "SSL_CERT_FILE", "SSL_CERT_DIR",
    ]

    fun curated(): Map<String, String> = fixed
    fun blocked(name: String): Boolean = toxic.any { name.uppercase().contains(it) }
}
