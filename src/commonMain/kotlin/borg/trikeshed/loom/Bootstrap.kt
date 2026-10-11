package borg.trikeshed.loom

import borg.trikeshed.ipns.*
import borg.trikeshed.lib.*
import borg.trikeshed.platform.*
import borg.trikeshed.userspace.*

/**
 * cocaine-rats crates/loom-mesh/src/bootstrap.rs: explicit RunPod load-balancer boot; credentials never enter trust
 * records. No Debug or Serialize: this value owns private invocation credentials. [key] is the 32-byte Ed25519 seed,
 * [tokens] each enrolled replica origin's `Authorization` value, [gcs] the injected service-account key (null in
 * lease mode, where [Node.open_inner] opens the leased archive, or without GCS).
 */
class Bootstrap(val config: Config, val key: ByteArray, val tokens: Map<String, String>, val gcs: ServiceAccountKey?) {
    companion object {
        /** bootstrap.rs `Document`'s fields, serde `deny_unknown_fields`; `gcs_credentials_json` defaults to None. */
        val DOCUMENT = s_["config", "signing_key_hex", "invocation_tokens", "gcs_credentials_json"]

        fun from_environment(): Bootstrap {
            val file = HostSystem.var_os("LOOM_MESH_BOOTSTRAP_FILE")
            val json = HostSystem.var_os("LOOM_MESH_BOOTSTRAP_JSON")
            val bytes = when {
                file != null && json == null && file.isNotEmpty() -> {
                    val ring = Config.ring()
                    try {
                        ring.protected_file(file.decodeToString(), 131072)
                    } finally {
                        ring.closeNow()
                    }
                }
                file == null && json != null -> json.also { utf8(it) ?: error("bootstrap encoding") }
                else -> error("exactly one mesh bootstrap source required")
            }
            try {
                val port = utf8(HostSystem.var_os("PORT")) ?: error("PORT required")
                val health = utf8(HostSystem.var_os("PORT_HEALTH")) ?: error("PORT_HEALTH required")
                return parse(bytes, port, health)
            } finally {
                bytes.fill(0)
            }
        }

        /** `OsString::into_string`: the text of valid UTF-8, else null. */
        fun utf8(bytes: ByteArray?): String? = try {
            bytes?.decodeToString(throwOnInvalidSequence = true)
        } catch (malformed: CharacterCodingException) {
            null
        }

        fun parse(bytes: ByteArray, port: String, health: String): Bootstrap {
            if (bytes.size > 131072) error("bootstrap size")
            val fields = document(bytes) ?: error("invalid mesh bootstrap")
            val config: Config
            val signing_key_hex: String
            val invocation_tokens: Map<String, String>
            val gcs_credentials_json: String?
            try {
                config = Config.deserialize(fields[0] ?: error("missing field `config`"))
                signing_key_hex = (fields[1] ?: error("missing field `signing_key_hex`")).deserialize_string("signing_key_hex")
                invocation_tokens = (fields[2] ?: error("missing field `invocation_tokens`")).let { value ->
                    if (value !is Map<*, *>) error("invalid type: `invocation_tokens`, expected a map")
                    value.entries.associate { (origin, token) -> origin as String to token!!.deserialize_string("invocation_tokens") }
                }
                gcs_credentials_json = fields[3]?.deserialize_option { it.deserialize_string("gcs_credentials_json") }
            } catch (refused: IllegalStateException) {
                error("invalid mesh bootstrap")
            }
            config.validate()
            val number = port_number(port) ?: error("invalid serverless port")
            if (number == 0 || port != number.toString() || health != port || !config.runpod_serverless ||
                config.listen.port != number || config.key_file.isNotEmpty()
            ) error("serverless bootstrap configuration mismatch")
            if (signing_key_hex.length != 64 || !signing_key_hex.all { it in '0'..'9' || it in 'a'..'f' }) error("invalid signing key encoding")
            val key = signing_key_hex.hexToByteArray()
            if (!Ed25519.publicKey(key).contentEquals(config.member(config.id).key())) {
                key.fill(0)
                error("local signing key mismatch")
            }
            val required = config.members.view.filter { it.has(Role.Replica) }.map { it.url!! }.toSet()
            // Empty map: lease mode; the keeper installs time-bounded tokens via signed /v1/lease. Otherwise every
            // enrolled replica needs one.
            if (invocation_tokens.isNotEmpty() && invocation_tokens.keys != required) error("invocation token membership mismatch")
            val tokens = token_headers(invocation_tokens)
            val gcs = config.gcs
            val credentials = when {
                gcs != null && gcs_credentials_json != null && gcs.service_account == null -> GcsArchive.service_account_key(gcs, gcs_credentials_json)
                // Lease mode: no stored Google credential in the document.
                gcs != null && gcs_credentials_json == null && gcs.service_account != null -> null
                gcs == null && gcs_credentials_json == null -> null
                else -> error("explicit GCS service-account credential required exactly when configured")
            }
            return Bootstrap(config, key, tokens, credentials)
        }

        /**
         * serde_json::from_slice into `Document`: an object holding each field at most once, or a sequence of them in
         * order, read with unique keys in every struct while the `invocation_tokens` BTreeMap keeps the last of a
         * repeated key. The values in [DOCUMENT] order, null where absent; null where serde refuses the bytes.
         */
        fun document(bytes: ByteArray): Array<Any?>? = try {
            val reader = JsonReader(bytes, unique = true)
            val values = arrayOfNulls<Any>(DOCUMENT.size)
            fun field(at: Int): Any = if (at != 2) reader.value() else JsonReader(bytes, unique = false).let { tokens ->
                tokens.at = reader.at
                tokens.remainingDepth = reader.remainingDepth
                tokens.value().also { reader.at = tokens.at }
            }
            reader.whitespace()
            when (reader.peek()) {
                '{'.code -> reader.nested {
                    reader.whitespace()
                    if (reader.peek() == '}'.code) reader.at++ else while (true) {
                        reader.whitespace()
                        require(reader.next() == '"'.code)
                        val key = reader.string()
                        val at = (0 until DOCUMENT.size).firstOrNull { DOCUMENT[it] == key }
                        require(at != null && values[at] == null)
                        reader.whitespace()
                        require(reader.next() == ':'.code)
                        values[at] = field(at)
                        reader.whitespace()
                        when (reader.next()) {
                            ','.code -> {}
                            '}'.code -> break
                            else -> throw IllegalArgumentException()
                        }
                    }
                }
                '['.code -> reader.nested {
                    var at = 0
                    reader.whitespace()
                    if (reader.peek() == ']'.code) reader.at++ else while (true) {
                        require(at < DOCUMENT.size)
                        values[at] = field(at)
                        at++
                        reader.whitespace()
                        when (reader.next()) {
                            ','.code -> {}
                            ']'.code -> break
                            else -> throw IllegalArgumentException()
                        }
                    }
                    // Every field but the defaulted `gcs_credentials_json` is required in a sequence.
                    require(at >= 3)
                }
                else -> throw IllegalArgumentException()
            }
            reader.whitespace()
            require(reader.at == bytes.size)
            values
        } catch (refused: IllegalArgumentException) {
            null
        }

        /** `str::parse::<u16>`: an optional `+` and ASCII digits within 0..=65535. */
        fun port_number(text: String): Int? {
            val digits = text.removePrefix("+")
            if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
            var n = 0
            for (c in digits) {
                n = n * 10 + (c - '0')
                if (n > 65535) return null
            }
            return n
        }
    }
}

/** Refuse an absent volume before Store can create an ephemeral directory. */
fun mounted_store(store: String) {
    val mount = "/runpod-volume"
    // Path::parent and Path::components: the store is exactly root, the mount, then one normal name.
    val names = components(store)
    if (PlatformHost.default.descriptor.os != "linux" || names.size != 3 || names[0] != "/" || names[1] != mount.substring(1) ||
        names[2] == "." || names[2] == ".." || !canonical(mount)
    ) error("serverless store requires a direct child of the mounted RunPod volume")
    val ring = Config.ring()
    val bytes = try {
        val fd = ring.open("/proc/self/mountinfo", UringOp.O_RDONLY, 0)
        try {
            ring.read_to_end(fd, 1024 * 1024 + 1)
        } finally {
            ring.close(fd)
        }
    } finally {
        ring.closeNow()
    }
    if (bytes.size > 1024 * 1024) error("mount table size")
    val table = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (malformed: CharacterCodingException) {
        error("mount table encoding")
    }
    val mounted = lines(table).any { line ->
        val split = line.indexOf(" - ")
        if (split < 0) return@any false
        val fields = split_whitespace(line.substring(0, split))
        val filesystem = split_whitespace(line.substring(split + 3)).firstOrNull()
        fields.size >= 6 && fields[4] == mount && fields[5].split(',').any { it == "rw" } &&
            filesystem != null && filesystem !in setOf("overlay", "tmpfs", "ramfs")
    }
    if (!mounted) error("writable persistent RunPod volume mount required")
}

/** std::path's Unix `components`: `/` for a leading root, a leading `.` kept, interior `.` and empty names dropped. */
fun components(path: String): List<String> = buildList {
    if (path.startsWith('/')) add("/")
    else if (path == "." || path.startsWith("./")) add(".")
    for (name in path.split('/')) if (name.isNotEmpty() && name != ".") add(name)
}

/** `fs::canonicalize(path) == path` for a name directly under `/`: it exists and is not a symlink. */
fun canonical(path: String): Boolean {
    val ring = Config.ring()
    return try {
        val meta = ring.symlink_metadata(path)
        meta != null && meta.stx_mode and UringOp.S_IFMT != 0xa000
    } catch (failed: IllegalStateException) {
        false
    } finally {
        ring.closeNow()
    }
}

/** `str::lines`: split after each `\n`, a line ending `\r\n` loses both, the last line needs no ending. */
fun lines(text: String): List<String> = buildList {
    var start = 0
    while (start < text.length) {
        val end = text.indexOf('\n', start)
        if (end < 0) {
            add(text.substring(start))
            break
        }
        add(text.substring(start, end).removeSuffix("\r"))
        start = end + 1
    }
}

/** `str::split_whitespace`: the pieces between Unicode White_Space characters, empty ones skipped. */
fun split_whitespace(text: String): List<String> = buildList {
    var start = 0
    for (i in 0..text.length) {
        val c = if (i < text.length) text[i].code else 32
        if (c in 9..13 || c == 32 || c == 0x85 || c == 0xA0 || c == 0x1680 || c in 0x2000..0x200A || c == 0x2028 ||
            c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000
        ) {
            if (i > start) add(text.substring(start, i))
            start = i + 1
        }
    }
}

/**
 * The `Authorization` value of each endpoint origin's invocation credential: at most 64, each origin an exact RunPod
 * endpoint and each token 32..=512 of `[A-Za-z0-9_-]`, checked in BTreeMap (byte) order.
 */
fun token_headers(tokens: Map<String, String>): Map<String, String> {
    if (tokens.size > 64) error("invocation token count")
    val headers = LinkedHashMap<String, String>()
    for (origin in tokens.keys.sortedWith { x, y -> codePointOrder(x, y) }) {
        runpod_origin(origin)
        val value = tokens.getValue(origin)
        if (value.length !in 32..512 || !value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' })
            error("invalid invocation credential")
        headers[origin] = "Bearer $value"
    }
    return headers
}
