package borg.trikeshed.userspace.reactor

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.util.io.ContentTypes
import borg.trikeshed.util.io.ForgeCliArgs
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.ForgeHome
import borg.trikeshed.util.oroboros.OroborosAttachmentRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * One canonical example that wires the consolidated forge framework:
 * kanban → oroboros (CouchAttachmentGateway) → daemon cycle event → TUI
 * saturation — all routed through a CCEK reactor element over a single
 * [ForgeReactorEvent] SharedFlow.
 *
 * Replaces the three scattered examples previously embedded in
 * `KanbanFSM`, `MuxReactorElement`, and `MuxReactorHud`. The point of this
 * file is to be read once and answer "how do these pieces fit together?"
 * Every other entrypoint (the JVM daemon, the litebike server, the Compose
 * shell) launches one of these.
 *
 * Run:
 *   ./gradlew jvmJar --no-daemon
 *   java -cp build/libs/TrikeShed-jvm-1.0.jar \
 *        borg.trikeshed.userspace.reactor.ForgeReactorExampleKt
 */
fun main() = runBlocking {
    val flags = listOf(
        ForgeCliArgs.Flag(
            name = "--interval-ms",
            withValue = true,
            action = { _, i -> i + 1 },
        ),
    )
    when (val parsed = ForgeCliArgs.parse(args = emptyList(), flags = flags)) {
        is ForgeCliArgs.Result.Parsed -> println("positional: ${parsed.remaining}")
        ForgeCliArgs.Result.Help -> return@runBlocking
        is ForgeCliArgs.Result.Error -> error(parsed.message)
    }

    val example = ForgeReactorExample(forgeHome = ForgeHome.defaultHome)
    example.open(parentJob = coroutineContext[Job]!!)
    try {
        example.runSyntheticCycle("hello forge")
        example.events.collect { /* TUI/HUD subscribers hook here */ }
    } finally {
        example.close()
    }
}

/**
 * The single example CCEK element. Opens an attachment gateway over an
 * in-memory CAS (real deployments swap in `FileCasStore` with JVM file
 * ops), emits one [ForgeReactorEvent.CycleObserved], and exposes the
 * SharedFlow for downstream consumers (TUI, daemon health-socket, litebike
 * worker).
 */
class ForgeReactorExample(
    private val forgeHome: String,
) {
    private val _events = MutableSharedFlow<ForgeReactorEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ForgeReactorEvent> = _events.asSharedFlow()

    private var scope: CoroutineScope? = null
    private var gateway: CouchAttachmentGateway? = null

    fun open(parentJob: Job) {
        val scope = CoroutineScope(SupervisorJob(parentJob) + Dispatchers.Default)
        this.scope = scope
        gateway = CouchAttachmentGateway(
            couchStore = CouchStoreFactory.inMemory(),
            casStore = CasStore.inMemory(),
        )
        scope.launch { _events.emit(ForgeReactorEvent.ReactorOpened(forgeHome)) }
    }

    /** Drive one attachment + cycle without needing the live Jules REST plane. */
    suspend fun runSyntheticCycle(payload: String) {
        val bytes = payload.encodeToByteArray()
        val ref = OroborosAttachmentRef(
            path = "examples/payload.txt",
            contentType = ContentTypes.forPath("payload.txt"),
            length = bytes.size.toLong(),
            contentId = ContentId.of(bytes),
            agentId = "forge-example",
            revision = "synthetic",
            sequence = 0L,
        )
        gateway?.putAttachment(ref, bytes)

        // The cycle event the daemon's JSONL trace and the TUI's saturation
        // bar both consume.
        _events.emit(
            ForgeReactorEvent.CycleObserved(
                cycleMs = 1L,
                drained = 1,
                dispatched = 0,
                alive = 1,
                available = 14,
                attachment = ref.toString(),
            )
        )
    }

    fun close() {
        scope?.cancel()
        scope = null
        gateway = null
    }
}

/**
 * Event vocabulary the consolidated forge framework publishes.
 * Subscribers: JSONL trace writer (daemon), wheel saturation view (TUI),
 * litebike workers, the Compose shell's HUD.
 */
sealed class ForgeReactorEvent {
    abstract val timestampMs: Long

    data class ReactorOpened(
        val forgeHome: String,
        override val timestampMs: Long = currentMs(),
    ) : ForgeReactorEvent()

    data class CycleObserved(
        val cycleMs: Long,
        val drained: Int,
        val dispatched: Int,
        val alive: Int,
        val available: Int,
        val attachment: String? = null,
        override val timestampMs: Long = currentMs(),
    ) : ForgeReactorEvent()

    data class KanbanCardMoved(
        val cardId: String,
        val fromColumn: String,
        val toColumn: String,
        override val timestampMs: Long = currentMs(),
    ) : ForgeReactorEvent()
}

private fun currentMs(): Long =
    kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
