package borg.trikeshed.lcnc

/**
 * CamelLinkage — which Camel endpoint schemes a route may name, and what each one reaches.
 *
 * A Camel route is two things at once: a control shape (the EIPs) and a set of endpoint
 * URIs. The control shape is inert — it moves an Exchange between processors inside one
 * JVM. The endpoints are where a route reaches the world, so they are the whole surface
 * worth governing, and `vm.camel` declares exactly two of them (`from`, `to`).
 *
 * TWO GATES, because departmentalizing gives the first one for free.
 *
 * The first is PROVISION: a scheme has to be on a mounted classpath. `smtp:` is not
 * refused by a rule — with only the `camel` spine mounted there is no MailComponent to
 * resolve, and mounting `camel-mail` is a deliberate, hash-pinned, MANIFEST-verified act.
 * So the department set already answers "may this deployment send mail" without any
 * policy, and a deployment that never bought mail cannot name an smtp endpoint at all.
 *
 * The second is REACH, and it exists because provision alone is too coarse once a
 * department is mounted: the operator who wanted an IMAP poller has, by the same act,
 * made SMTP nameable from every other route on the box. [Reach.LOCAL] — the default —
 * admits only schemes that stay inside the machine, so touching the wire has to be
 * spelled out in the node's own params, where it is visible in the graph rather than
 * implied by what someone installed months ago.
 *
 * The table is data rather than a predicate for the same reason
 * [borg.trikeshed.graal.BlackboardNamespaces] is: a row carries its meaning and its
 * provider next to its name, and a new component means a new row, not a new condition.
 * It fails closed — a scheme with no row is [Locality.NETWORK] and belongs to no module,
 * so it is refused twice over until someone writes down what it reaches.
 */
data object CamelLinkage {

    /** What a scheme reaches when a route names it. */
    enum class Locality {
        /** The filesystem and the process namespace — POSIX proper. */
        POSIX,
        /** In-process handoff: a queue, a holder, a stub. Never leaves the JVM. */
        MEMORY,
        /** Self-firing on a clock. Reaches nothing; produces Exchanges. */
        CLOCK,
        /** Pure transformation over the Exchange body. */
        COMPUTE,
        /** The CamelContext talking about itself — routes, logs, sagas. */
        CONTROL,
        /** Declared, but inert without a transport provider that is not mounted. */
        PROVIDER,
        /** Reaches the wire. Admitted only under [Reach.DEPARTMENT]. */
        NETWORK,
    }

    /** How far a route is allowed to reach, declared on the node rather than inherited. */
    enum class Reach {
        /** Everything stays on this machine. The default, and what an unset param means. */
        LOCAL,
        /** Anything a mounted department provides, the wire included. */
        DEPARTMENT,
    }

    /** One row: the scheme, what it is, what it reaches, and which guest module ships it. */
    data class Linkage(val scheme: String, val meaning: String, val locality: Locality, val module: String)

    /** The guest module carrying the EIP engine and the components that ship with core. */
    const val SPINE = "camel"

    /**
     * Every component scheme a known module ships, as enumerated from the
     * `META-INF/services/org/apache/camel/component/` service files in its jars.
     * [unlisted] reports drift between this table and a mount, as data rather than as a
     * surprise at route build.
     */
    val known: List<Linkage> = listOf(
        // ── the spine: camel-core + camel-main ──────────────────────────────────────
        Linkage("file", "read and write files, with idempotent consumption and .done markers", Locality.POSIX, SPINE),

        Linkage("direct", "synchronous in-process call, same thread", Locality.MEMORY, SPINE),
        Linkage("seda", "in-memory staged queue, NOT durable across a restart", Locality.MEMORY, SPINE),
        Linkage("stub", "a direct endpoint that discards — stands in for one not mounted", Locality.MEMORY, SPINE),
        Linkage("mock", "assertion endpoint used by tests", Locality.MEMORY, SPINE),
        Linkage("browse", "in-memory tap that retains what passed through it", Locality.MEMORY, SPINE),
        Linkage("ref", "indirection to another endpoint held in the registry", Locality.MEMORY, SPINE),
        Linkage("dataset", "generated load for exercising a route", Locality.MEMORY, SPINE),
        Linkage("dataset-test", "dataset variant that compares against expected bodies", Locality.MEMORY, SPINE),

        Linkage("timer", "fires on a period; the ordinary route clock", Locality.CLOCK, SPINE),
        Linkage("scheduler", "fires on a schedule with a thread pool behind it", Locality.CLOCK, SPINE),

        Linkage("bean", "invoke a method on a registry bean", Locality.COMPUTE, SPINE),
        Linkage("class", "invoke a method on a newly constructed class", Locality.COMPUTE, SPINE),
        Linkage("language", "evaluate a script in one of the mounted languages", Locality.COMPUTE, SPINE),
        Linkage("xslt", "XSLT transformation of the body", Locality.COMPUTE, SPINE),
        Linkage("validator", "validate the body against a schema", Locality.COMPUTE, SPINE),
        Linkage("dataformat", "marshal or unmarshal through a named data format", Locality.COMPUTE, SPINE),

        Linkage("log", "write the Exchange to the logger", Locality.CONTROL, SPINE),
        Linkage("controlbus", "start, stop and interrogate the context's own routes", Locality.CONTROL, SPINE),
        Linkage("saga", "compensating-transaction coordination", Locality.CONTROL, SPINE),

        Linkage("rest", "REST DSL binding — inert without a platform-http provider", Locality.PROVIDER, SPINE),
        Linkage("rest-api", "the generated API description for the REST DSL", Locality.PROVIDER, SPINE),

        // ── department: camel-mail (4 jars, ~1MB on top of the spine) ───────────────
        Linkage("imap", "poll an IMAP mailbox", Locality.NETWORK, "camel-mail"),
        Linkage("imaps", "poll an IMAP mailbox over TLS", Locality.NETWORK, "camel-mail"),
        Linkage("pop3", "poll a POP3 mailbox", Locality.NETWORK, "camel-mail"),
        Linkage("pop3s", "poll a POP3 mailbox over TLS", Locality.NETWORK, "camel-mail"),
        Linkage("smtp", "send mail", Locality.NETWORK, "camel-mail"),
        Linkage("smtps", "send mail over TLS", Locality.NETWORK, "camel-mail"),
    )

    private val byScheme: Map<String, Linkage> = known.associateBy { it.scheme }

    /** The modules this table describes, in the order they are met. */
    fun modules(): List<String> = known.map { it.module }.distinct()

    /** The rows one module ships. */
    fun of(module: String): List<Linkage> = known.filter { it.module == module }

    /** The scheme of an endpoint URI: everything before the first `:`. Blank when there is none. */
    fun schemeOf(uri: String): String {
        val i = uri.indexOf(':')
        return if (i <= 0) "" else uri.substring(0, i).trim().lowercase()
    }

    /** What this URI reaches — [Locality.NETWORK] for anything with no row, the fail-closed side. */
    fun localityOf(uri: String): Locality = byScheme[schemeOf(uri)]?.locality ?: Locality.NETWORK

    /** Which guest module ships this URI's scheme, or null when the table has no row for it. */
    fun moduleOf(uri: String): String? = byScheme[schemeOf(uri)]?.module

    /** Whether a locality stays on this machine. [Locality.PROVIDER] does, but cannot start. */
    private fun isLocal(l: Locality) = when (l) {
        Locality.NETWORK, Locality.PROVIDER -> false
        else -> true
    }

    /**
     * Is this endpoint admissible, given what is mounted and how far the node says it reaches?
     *
     * [mounted] is the module chain of the VM this route will run in — `["camel"]` for the
     * spine alone, `["camel-mail", "camel"]` for the mail department. Passing it in rather
     * than looking it up keeps this file free of any platform, which is where the rest of
     * the guest-module vocabulary already lives.
     */
    fun admits(uri: String, reach: Reach = Reach.LOCAL, mounted: Collection<String> = listOf(SPINE)): Boolean =
        refusal(uri, reach, mounted) == null

    /**
     * Why this URI is refused, or null when it is admitted. The message names the scheme and
     * what would have to change, because "refused" without the remedy sends an operator to
     * read source that tells them nothing they could not have guessed.
     */
    fun refusal(
        uri: String,
        reach: Reach = Reach.LOCAL,
        mounted: Collection<String> = listOf(SPINE),
    ): String? {
        if (uri.isBlank()) return "endpoint URI is blank"
        val scheme = schemeOf(uri)
        if (scheme.isEmpty()) return "endpoint '$uri' has no scheme — a Camel URI is '<scheme>:<rest>'"
        val row = byScheme[scheme]
            ?: return "scheme '$scheme' is not listed in CamelLinkage.known, so nothing records what it " +
                "reaches; admitted here: ${admissible(reach, mounted).joinToString(", ")}"
        if (row.module !in mounted) {
            return "scheme '$scheme' is shipped by guest module '${row.module}', which is not mounted — " +
                "mount it with VmSpec.module = \"${row.module}\" " +
                "(install: ./gradlew -p utils/subvm install${gradleName(row.module)})"
        }
        if (row.locality == Locality.PROVIDER) {
            return "scheme '$scheme' needs a transport provider that is not mounted, so a route " +
                "naming it cannot start"
        }
        if (reach == Reach.LOCAL && !isLocal(row.locality)) {
            return "scheme '$scheme' reaches the network (${row.meaning}) and this node declares " +
                "reach=LOCAL — set reach=DEPARTMENT to allow it, so that touching the wire is " +
                "visible in the graph rather than implied by what is installed"
        }
        return null
    }

    /** Every scheme admitted under this reach and this mount, sorted — what a route may name. */
    fun admissible(reach: Reach = Reach.LOCAL, mounted: Collection<String> = listOf(SPINE)): List<String> =
        known.filter { it.module in mounted && refusal(it.scheme + ":x", reach, mounted) == null }
            .map { it.scheme }.sorted()

    /** Backwards-compatible spelling of [admissible] for the spine under [Reach.LOCAL]. */
    fun localSchemes(): List<String> = admissible()

    /** Schemes present on disk but absent from [known]: drift, reported as data. */
    fun unlisted(installedSchemes: Collection<String>): List<String> =
        installedSchemes.filter { it !in byScheme }.sorted()

    /** `camel-mail` -> `CamelMail`, so a refusal can name a task that actually exists. */
    private fun gradleName(module: String): String =
        module.split('-').filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
}
