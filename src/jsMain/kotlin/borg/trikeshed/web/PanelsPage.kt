package borg.trikeshed.web

import borg.trikeshed.lcnc.PanelsElement
import kotlinx.browser.window
import kotlinx.coroutines.*
import kotlin.js.Promise

/** One mounted page's browser handles; actual work belongs to the common bounded lanes. */
internal class PanelsPage(private val failed: (Throwable) -> Unit) {
    private class Binding(val target: dynamic, val type: String, val handler: (dynamic) -> Unit,
                          val admitted: (dynamic) -> Unit, val options: dynamic)
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + supervisor)
    val element = PanelsElement(supervisor)
    private val lanes = mutableListOf(element)
    var closing = false
        private set
    private val releases = mutableListOf<() -> Unit>()
    private val bindings = mutableListOf<Binding>()
    private val timeouts = mutableSetOf<Int>()
    private val frames = mutableSetOf<Int>()
    private var releaseFailure: Throwable? = null
    private var startup: Deferred<Unit>? = null
    private var teardown: Deferred<Unit>? = null
    private var shutdownFailure: Throwable? = null
    private val completion = Promise<Unit> { resolve, reject ->
        supervisor.invokeOnCompletion { cause ->
            val failure = shutdownFailure ?: cause
            if (failure == null) resolve(Unit) else reject(failure)
        }
    }

    init {
        val leaving: (dynamic) -> Unit = { close() }
        window.addEventListener("pagehide", leaving)
        own { window.removeEventListener("pagehide", leaving) }
    }

    /** Ordered input can share the page lifetime without sharing its background workers. */
    fun lane(workers: Int, maxActions: Int = 64): PanelsElement {
        check(startup == null && !closing) { "Page lanes must be declared before mount" }
        return PanelsElement(supervisor, workers = workers, maxActions = maxActions).also { lanes.add(it) }
    }

    /** The returned function removes a resource that already completed from this mount's cleanup. */
    fun own(release: () -> Unit): () -> Unit {
        if (closing) release() else releases.add(release)
        return { releases.remove(release); Unit }
    }

    fun on(target: dynamic, type: String, handler: (dynamic) -> Unit, options: dynamic = undefined) {
        if (closing) return
        val admitted: (dynamic) -> Unit = { if (!closing) handler(it) }
        borg.trikeshed.web.on(target, type, admitted, options)
        bindings.add(Binding(target, type, handler, admitted, options))
    }

    fun off(target: dynamic, type: String, handler: (dynamic) -> Unit) {
        val index = bindings.indexOfFirst { it.target === target && it.type == type && it.handler === handler }
        if (index < 0) return
        val binding = bindings.removeAt(index)
        borg.trikeshed.web.off(binding.target, binding.type, binding.admitted, binding.options)
    }

    fun later(ms: Int, block: () -> Unit): Int {
        if (closing) return 0
        var handle = 0
        handle = window.setTimeout({ timeouts.remove(handle); if (!closing) block() }, ms)
        timeouts.add(handle)
        return handle
    }

    fun cancelLater(handle: Int) { timeouts.remove(handle); window.clearTimeout(handle) }

    fun frame(block: (Double) -> Unit): Int {
        if (closing) return 0
        var handle = 0
        handle = window.requestAnimationFrame { time -> frames.remove(handle); if (!closing) block(time) }
        frames.add(handle)
        return handle
    }

    fun mount(install: () -> Unit) {
        check(startup == null && !closing) { "Page mount already consumed" }
        val opened = scope.async(start = CoroutineStart.LAZY) {
            for (lane in lanes) if (!closing) lane.open()
            if (!closing) install()
        }
        startup = opened
        opened.invokeOnCompletion { failure -> if (failure != null) try { failed(failure) } finally { close() } }
        opened.start()
    }

    fun submit(action: suspend () -> Unit): Deferred<Unit>? = element.submit(action).also { completion ->
        if (completion == null) {
            if (!closing) failed(IllegalStateException("Page action capacity is full"))
        } else completion.invokeOnCompletion { failure -> if (failure != null && !closing) failed(failure) }
    }

    /** Await this before replacing the mount. pagehide initiates it but cannot await document survival. */
    fun close(): Promise<Unit> {
        if (!closing) {
            closing = true
            for (lane in lanes) lane.stopAdmission()
            while (bindings.isNotEmpty()) try {
                val binding = bindings.removeAt(bindings.lastIndex)
                borg.trikeshed.web.off(binding.target, binding.type, binding.admitted, binding.options)
            } catch (failure: Throwable) {
                if (releaseFailure == null) releaseFailure = failure
                else if (releaseFailure !== failure) releaseFailure?.addSuppressed(failure)
            }
            for (handle in timeouts) window.clearTimeout(handle)
            timeouts.clear()
            for (handle in frames) window.cancelAnimationFrame(handle)
            frames.clear()
            while (releases.isNotEmpty()) try { releases.removeAt(releases.lastIndex).invoke() }
            catch (failure: Throwable) {
                if (releaseFailure == null) releaseFailure = failure
                else if (releaseFailure !== failure) releaseFailure?.addSuppressed(failure)
            }
        }
        if (teardown != null) return completion
        val drained = scope.async(start = CoroutineStart.LAZY) {
            var failure = releaseFailure
            try { startup?.await() }
            catch (caught: Throwable) {
                if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
            }
            for (lane in lanes) try { lane.drain() }
            catch (caught: Throwable) {
                if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
            }
            shutdownFailure = failure
        }
        teardown = drained
        // Completion follows the joined common lanes; no lifecycle job joins its own parent.
        drained.invokeOnCompletion { failure ->
            if (failure != null) shutdownFailure = failure
            supervisor.complete()
        }
        drained.start()
        return completion
    }
}
