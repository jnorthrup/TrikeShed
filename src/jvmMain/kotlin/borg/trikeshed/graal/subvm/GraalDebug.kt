package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.*
import com.oracle.truffle.api.debug.*
import kotlinx.coroutines.*
import org.graalvm.polyglot.Engine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.ContinuationInterceptor

/** Synchronous Truffle boundary only: the existing guest thread waits on the caller's common controller. */
internal class GraalDebug(engine: Engine, sourceName: String, private val request: VmDebugRequest) : AutoCloseable {
    private val waiting = AtomicReference<Job?>()
    private val failure = AtomicReference<Throwable?>()
    @Volatile private var stopped = false
    private var lineBreakpoint: Breakpoint? = null
    private var requestedLine: Int? = null
    private val session = Debugger.find(engine).startSession({ event ->
        if (stopped) event.prepareKill()
        else try {
            // Truffle frames/values are valid only here, on the suspended guest thread.
            val snapshot = snapshot(event)
            lineBreakpoint?.dispose()
            lineBreakpoint = null
            requestedLine = null
            val action = runBlocking(request.context.minusKey(ContinuationInterceptor)) {
                waiting.set(currentCoroutineContext().job)
                try {
                    if (stopped) throw CancellationException("Guest debug session interrupted")
                    runCatching { request.suspendAt(snapshot) }
                } finally { waiting.set(null) }
            }.getOrThrow()
            when (action) {
                VmDebugAction.INTO -> event.prepareStepInto(1)
                VmDebugAction.OVER -> event.prepareStepOver(1)
                VmDebugAction.OUT -> event.prepareStepOut(1)
                VmDebugAction.CONTINUE -> event.prepareContinue()
                is VmDebugAction.Line -> {
                    val source = checkNotNull(event.sourceSection?.source) { "Guest source unavailable" }
                    require(action.number in 1..source.lineCount) { "Line is outside the current guest source" }
                    requestedLine = action.number
                    // Ruby tail expressions can expose only their enclosing block's root boundary.
                    // Use the same executable elements as stepping and report the actual hit location.
                    lineBreakpoint = event.session.install(Breakpoint.newBuilder(source).lineIs(action.number)
                        .sourceElements(SourceElement.STATEMENT, SourceElement.ROOT).oneShot().build())
                    event.prepareContinue()
                }
            }
        } catch (_: CancellationException) { event.prepareKill() }
        catch (_: InterruptedException) { event.prepareKill() }
        catch (cause: Throwable) {
            // Truffle otherwise logs a callback failure and resumes the guest.
            failure.compareAndSet(null, cause)
            event.prepareKill()
        }
    }, SourceElement.STATEMENT, SourceElement.ROOT).apply {
        setSteppingFilter(SuspensionFilter.newBuilder().ignoreLanguageContextInitialization(true)
            .includeInternal(false).sourceIs { it.name == sourceName }.build())
        suspendNextExecution()
    }

    /** Wake a channel-held callback before the existing isolate interrupt waits for a safepoint. */
    fun interrupt() {
        stopped = true
        waiting.get()?.cancel(CancellationException("Guest debug session interrupted"))
    }

    override fun close() {
        interrupt()
        lineBreakpoint?.dispose()
        lineBreakpoint = null
        session.close()
        failure.get()?.let { throw IllegalStateException("Guest source inspection failed", it) }
    }

    private fun snapshot(event: SuspendedEvent): Map<String, Any?> {
        val location = event.sourceSection
        var omitted = false
        val frames = ArrayList<Map<String, Any?>>()
        val stack = event.stackFrames.iterator()
        while (stack.hasNext() && frames.size < 8) {
            val frame = stack.next()
            val locals = ArrayList<Map<String, Any?>>()
            var scope = frame.scope
            var scopes = 0
            while (scope != null && locals.size < 16 && scopes++ < 4) {
                val values = scope.declaredValues.iterator()
                while (values.hasNext() && locals.size < 16) {
                    val value = values.next()
                    val row = linkedMapOf<String, Any?>("name" to value.name?.take(128))
                    try {
                        when {
                            !value.isReadable || value.hasReadSideEffects() -> row["omitted"] = "unreadable_or_side_effects"
                            value.isNull -> row["value"] = null
                            value.isBoolean -> row["value"] = value.asBoolean()
                            value.isString -> {
                                val text = value.asString()
                                row["value"] = text.take(256)
                                if (text.length > 256) row["truncated"] = true
                            }
                            value.isNumber && value.fitsInLong() -> row["value"] = value.asLong()
                            value.isNumber && value.fitsInDouble() -> {
                                val number = value.asDouble()
                                if (number.isFinite()) row["value"] = number else row["omitted"] = "nonfinite_number"
                            }
                            else -> row["omitted"] = "non_scalar"
                        }
                    } catch (_: DebugException) { row["omitted"] = "unavailable" }
                    locals.add(row)
                }
                if (values.hasNext()) omitted = true
                scope = scope.parent
            }
            if (scope != null) omitted = true
            frames.add(mapOf("name" to frame.name?.take(256), "line" to frame.sourceSection?.startLine,
                "locals" to locals))
        }
        if (stack.hasNext()) omitted = true
        val chars = location?.characters
        if (chars != null && chars.length > 2048) omitted = true
        val source = location?.source?.takeIf { it.hasCharacters() }
        val lineCount = source?.lineCount ?: 0
        val first = maxOf(1, (location?.startLine ?: 1) - 8)
        val lines = if (source == null) emptyList() else (first..minOf(lineCount, first + 16)).map { line ->
            val text = source.getCharacters(line)
            if (text.length > 512) omitted = true
            text.subSequence(0, minOf(text.length, 512)).toString()
        }
        val breakpoint = lineBreakpoint?.takeIf { it in event.breakpoints }?.let {
            mapOf("hit" to true, "requestedLine" to requestedLine, "resolvedLine" to location?.startLine)
        }
        return mapOf("sourceName" to location?.source?.name?.take(256), "line" to location?.startLine,
            "lineCount" to lineCount, "lineStart" to first, "sourceLines" to lines, "breakpoint" to breakpoint,
            "anchor" to event.suspendAnchor.name.lowercase(),
            "element" to if (event.hasSourceElement(SourceElement.ROOT)) "function" else "statement",
            "column" to location?.startColumn, "code" to chars?.subSequence(0, minOf(chars.length, 2048))?.toString(),
            "coordinates" to "generated-guest-source", "frames" to frames, "truncated" to omitted)
    }
}
