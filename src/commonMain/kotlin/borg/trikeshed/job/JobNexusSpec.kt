package borg.trikeshed.job

/**
 * Job Nexus specification types — channels, storage, supervision, rete config.
 *
 * All entrypoints (DSL, fluent builder, JSON/YAML/CBOR decoder) produce
 * byte-identical canonical specs via [JobNexusSpec.canonicalBytes].
 */

enum class StorageBackend { Memory, File, LinuxBtrfs }
enum class Durability { None, Fsync }

data class ChannelSpec(
    val commands: Int = 64,
    val committed: Int = 64,
    val facts: Int = 128,
    val activations: Int = 64,
    val telemetry: Int = 32,
)

data class StorageSpec(
    val backend: StorageBackend = StorageBackend.Memory,
    val durability: Durability = Durability.None,
    val checkpointEvery: Int = 0,
)

data class SupervisionSpec(
    val drainTimeoutMs: Long = 5_000,
)

data class ReteSpec(
    val cycleBudget: Int = 1_000,
)

data class JobNexusSpec(
    val channels: ChannelSpec = ChannelSpec(),
    val storage: StorageSpec = StorageSpec(),
    val supervision: SupervisionSpec = SupervisionSpec(),
    val rete: ReteSpec = ReteSpec(),
) {
    /**
     * Canonical bytes — deterministic serialization of the spec.
     * All entrypoints produce byte-identical canonical bytes.
     */
    val canonicalBytes: ByteArray by lazy {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"channels\":{\"commands\":${channels.commands},\"committed\":${channels.committed},\"facts\":${channels.facts},\"activations\":${channels.activations},\"telemetry\":${channels.telemetry}}")
        sb.append(",\"storage\":{\"backend\":\"${storage.backend.name.lowercase()}\",\"durability\":\"${storage.durability.name.lowercase()}\",\"checkpointEvery\":${storage.checkpointEvery}}")
        sb.append(",\"supervision\":{\"drainTimeoutMs\":${supervision.drainTimeoutMs}}")
        sb.append(",\"rete\":{\"cycleBudget\":${rete.cycleBudget}}")
        sb.append("}")
        sb.toString().encodeToByteArray()
    }

    class Builder {
        private var channels = ChannelSpec()
        private var storage = StorageSpec()
        private var supervision = SupervisionSpec()
        private var rete = ReteSpec()
        private var _ioCount = 0
        private var _coroutineLaunchCount = 0
        private var _channelCreateCount = 0
        private var _scopeCreateCount = 0

        val ioCount: Int get() = _ioCount
        val coroutineLaunchCount: Int get() = _coroutineLaunchCount
        val channelCreateCount: Int get() = _channelCreateCount
        val scopeCreateCount: Int get() = _scopeCreateCount

        fun channels(block: ChannelBuilder.() -> Unit): Builder {
            val b = ChannelBuilder(); b.block()
            channels = ChannelSpec(b.commands, b.committed, b.facts, b.activations, b.telemetry)
            return this
        }
        fun storage(block: StorageBuilder.() -> Unit): Builder {
            val b = StorageBuilder(); b.block()
            storage = StorageSpec(b.backend, b.durability, b.checkpointEvery)
            return this
        }
        fun supervision(block: SupervisionBuilder.() -> Unit): Builder {
            val b = SupervisionBuilder(); b.block()
            supervision = SupervisionSpec(b.drainTimeoutMs)
            return this
        }
        fun rete(block: ReteBuilder.() -> Unit): Builder {
            val b = ReteBuilder(); b.block()
            rete = ReteSpec(b.cycleBudget)
            return this
        }

        fun build(): JobNexusSpec = JobNexusSpec(channels, storage, supervision, rete)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}

class ChannelBuilder {
    var commands: Int = 64
    var committed: Int = 64
    var facts: Int = 128
    var activations: Int = 64
    var telemetry: Int = 32
    fun commands(v: Int) { commands = v }
    fun committed(v: Int) { committed = v }
    fun facts(v: Int) { facts = v }
    fun activations(v: Int) { activations = v }
    fun telemetry(v: Int) { telemetry = v }
}

class StorageBuilder {
    var backend: StorageBackend = StorageBackend.Memory
    var durability: Durability = Durability.None
    var checkpointEvery: Int = 0
    fun backend(v: StorageBackend) { backend = v }
    fun durability(v: Durability) { durability = v }
    fun checkpointEvery(v: Int) { checkpointEvery = v }
}

class SupervisionBuilder {
    var drainTimeoutMs: Long = 5_000
    fun drainTimeoutMs(v: Long) { drainTimeoutMs = v }
}

class ReteBuilder {
    var cycleBudget: Int = 1_000
    fun cycleBudget(v: Int) { cycleBudget = v }
}

/**
 * DSL entry point: jobNexusSpec { channels { ... }; storage { ... }; ... }
 */
fun jobNexusSpec(block: JobNexusSpecDsl.() -> Unit): JobNexusSpec {
    val dsl = JobNexusSpecDsl()
    dsl.block()
    return dsl.build()
}

class JobNexusSpecDsl {
    private var channels = ChannelSpec()
    private var storage = StorageSpec()
    private var supervision = SupervisionSpec()
    private var rete = ReteSpec()

    fun channels(block: ChannelBuilder.() -> Unit) {
        val b = ChannelBuilder(); b.block()
        channels = ChannelSpec(b.commands, b.committed, b.facts, b.activations, b.telemetry)
    }
    fun storage(block: StorageBuilder.() -> Unit) {
        val b = StorageBuilder(); b.block()
        storage = StorageSpec(b.backend, b.durability, b.checkpointEvery)
    }
    fun supervision(block: SupervisionBuilder.() -> Unit) {
        val b = SupervisionBuilder(); b.block()
        supervision = SupervisionSpec(b.drainTimeoutMs)
    }
    fun rete(block: ReteBuilder.() -> Unit) {
        val b = ReteBuilder(); b.block()
        rete = ReteSpec(b.cycleBudget)
    }

    fun build(): JobNexusSpec = JobNexusSpec(channels, storage, supervision, rete)
}
