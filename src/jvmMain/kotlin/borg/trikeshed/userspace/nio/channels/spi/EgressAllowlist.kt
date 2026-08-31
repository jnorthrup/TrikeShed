package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import keymux.HarnessRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * The HTX substrate's egress policy: deny by default, permit deliberately.
 *
 * The deny-by-default posture is the point and is kept. What was broken was
 * WHERE the permit list lived. `JvmChannelOperations.connect` hardcoded four
 * hosts — loopback, github, jules — and every LLM provider was reachable only
 * if the operator had exported `TRIKESHED_EGRESS_ALLOWLIST` with the right
 * spelling. `bin/oroboros-daemon` did export one, hand-maintained in bash,
 * beside a provider table that already knew every host. The two drifted, as two
 * copies of one list always do:
 *
 *   - the script said `api.moonshot.cn`; the registry says `api.moonshot.ai`,
 *     so moonshot and kimi could never connect
 *   - mistral, google/gemini, synthetic and opencode were never in the script
 *     at all, so they could never connect either
 *   - anything NOT launched through `bin/oroboros-daemon` — a test, a CLI, a
 *     fresh `ModelMux(...)` in someone's main() — got the four-host default and
 *     could reach no provider whatsoever
 *
 * That last case is why keymux/modelmux had no successful wire-up outside the
 * daemon: keys resolved, routing worked, the request was built correctly, and
 * the socket was refused by our own substrate one layer below where anyone was
 * looking. The failure surfaced as `SecurityException: host not in allowlist`
 * from inside NIO, naming no remedy.
 *
 * So the permit list is DERIVED from [HarnessRegistry], which is already the
 * single source of truth for provider hosts and is explicitly additive: a new
 * provider row now carries its own egress permission, and there is no second
 * list to forget. Three lanes, all still explicit:
 *
 *   1. [baseline]   — loopback plus the two service hosts the daemon needs.
 *   2. registry     — the host of every `HarnessProvider.defaultBaseUrl`.
 *   3. operator     — `TRIKESHED_EGRESS_ALLOWLIST`, and [allow]/[allowUrl] for
 *                     a base_url that came from env or hermes rather than the
 *                     registry default (a custom endpoint, a proxy, a gateway).
 *
 * This does not widen egress to the internet: a host still has to be a provider
 * this product is built to talk to, or one the operator named on purpose.
 */
object EgressAllowlist {

    /** Loopback, plus the two non-provider hosts the daemon itself requires. */
    private val baseline = setOf(
        "127.0.0.1", "0.0.0.0", "::1", "localhost",
        "github.com", "jules.googleapis.com",
    )

    /** Runtime additions: [allow] / [allowUrl]. Concurrent — connect() is on IO workers. */
    private val registered: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Hosts of every provider base_url in the registry. Lazy so the registry is
     * touched on first connect rather than at class-load of the NIO substrate.
     */
    private val registryHosts: Set<String> by lazy {
        val out = LinkedHashSet<String>()
        for (i in 0 until HarnessRegistry.providers.size) {
            HarnessRegistry.providers[i].defaultBaseUrl?.let { url -> hostOf(url)?.let(out::add) }
        }
        out
    }

    private val envHosts: Set<String> by lazy {
        System.getenv("TRIKESHED_EGRESS_ALLOWLIST")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            // Operators paste URLs as often as hosts; accept both rather than
            // failing silently on a trailing /v1 that looks correct to the eye.
            ?.mapNotNull { if ("://" in it) hostOf(it) else it }
            ?.toSet()
            ?: emptySet()
    }

    /**
     * The host of a URL, or null if [url] has none. Deliberately small: no URI
     * parsing, because a malformed base_url must not throw on the connect path.
     */
    fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", url)
        val hostPort = afterScheme.substringBefore('/').substringBefore('?')
        // Strip an explicit port and any userinfo.
        val host = hostPort.substringAfterLast('@').substringBefore(':')
        return host.ifBlank { null }
    }

    /** Permit [host] for the life of this process. Idempotent. */
    fun allow(host: String) {
        host.trim().takeIf { it.isNotEmpty() }?.let { registered.add(it) }
    }

    /**
     * Permit whatever host [url] names. This is the call a wiring path makes for
     * a resolved base_url: a key that came from hermes or `<ID>_BASE_URL` can
     * point at a host the registry never listed, and refusing it at the socket
     * would reproduce exactly the bug this object exists to end.
     */
    fun allowUrl(url: String) {
        hostOf(url)?.let(::allow)
    }

    fun permits(host: String): Boolean =
        host in baseline || host in registered || host in registryHosts || host in envHosts

    /** Every permitted host, for diagnostics. Sorted for stable output. */
    fun snapshot(): List<String> =
        (baseline + registryHosts + envHosts + registered).sorted()

    /**
     * The message a refusal carries. It names the knob, because the previous one
     * named only the verdict and cost the reader a trip into the NIO substrate
     * to discover an allowlist existed at all.
     */
    fun refusalMessage(host: String, port: Int): String =
        "egress denied by substrate: $host:$port is not an allowed host. " +
            "Permit it with TRIKESHED_EGRESS_ALLOWLIST=$host (comma-separated), " +
            "or add the provider to keymux.HarnessRegistry so its host is derived. " +
            "Currently allowed: ${snapshot().joinToString(",")}"
}
