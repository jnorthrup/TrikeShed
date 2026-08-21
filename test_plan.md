1. **Rewrite `UserspaceIO.js.kt`**
    - The `UserspaceIO.js.kt` file is structurally corrupt. I will use `run_in_bash_session` to write a fresh file using `cat << 'EOF' > src/jsMain/kotlin/borg/trikeshed/userspace/UserspaceIO.js.kt` mimicking `UserspaceIO.wasm.kt`'s structure and incorporating Fetch-backed read-only file logic (no NPM).
    - ```kotlin
package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission

actual class FileImpl actual constructor(actual val id: Int) {
    actual fun isOpen(): Boolean = id >= 0
    actual fun close() {}
    actual fun size(): Long = -1L
}

internal actual object FilesImpl {
    private var nextId = 1
    actual fun open(path: String, readOnly: Boolean): FileImpl {
        return FileImpl(nextId++)
    }
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = FileImpl(-1)
}

private class JsUserspaceChannelBackend : UserspaceChannelBackend {
    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> = emptyList()
    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> = Series(0) { UringCompletion(0, 0, 0, 0) }
}

actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend = JsUserspaceChannelBackend()
```
2. **Verify `UserspaceIO.js.kt` update**
    - Call `run_in_bash_session` with `cat src/jsMain/kotlin/borg/trikeshed/userspace/UserspaceIO.js.kt` to ensure it is correct.
3. **Implement `BrowserForgeWindowManager` methods**
    - Use `run_in_bash_session` with `cat << 'EOF' > src/jsMain/kotlin/borg/trikeshed/forge/window/BrowserForgeWindowManager.kt` to implement `bind(html)`, `injectScript(ScriptSnippet)`, `dispatchEvent(WindowEvent)`, and `captureSnapshot(): WindowSnapshot` as requested.
    - ```kotlin
package borg.trikeshed.forge.window

import kotlin.time.TimeSource

class BrowserForgeWindowManager : ForgeWindowManager {
    private var boundHtml: String = ""
    private val injected = mutableListOf<ScriptSnippet>()
    private val events = mutableListOf<WindowEvent>()

    override fun launch(html: String) {
        val isBrowser = js("typeof window !== 'undefined' && typeof document !== 'undefined'") as Boolean
        if (isBrowser) {
            js("document.open(); document.write(html); document.close();")
        }
    }

    override fun bind(html: String) {
        boundHtml = html
        val isBrowser = js("typeof window !== 'undefined' && typeof document !== 'undefined'") as Boolean
        if (isBrowser) {
            js("document.body.innerHTML = html;")
        }
    }

    override fun injectScript(snippet: ScriptSnippet) {
        injected.add(snippet)
        val isBrowser = js("typeof window !== 'undefined' && typeof document !== 'undefined'") as Boolean
        if (isBrowser) {
            val scriptCode = snippet.source
            js("var s = document.createElement('script'); s.type = 'text/javascript'; s.text = scriptCode; document.head.appendChild(s);")
        }
    }

    override fun dispatchEvent(event: WindowEvent) {
        events.add(event)
        val isBrowser = js("typeof window !== 'undefined' && typeof document !== 'undefined'") as Boolean
        if (isBrowser) {
            val type = event.type
            val payload = event.payload
            js("var e = new CustomEvent(type, { detail: payload }); window.dispatchEvent(e);")
        }
    }

    override fun captureSnapshot(): WindowSnapshot = WindowSnapshot(
        timestampMillis = TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds,
        dom = boundHtml,
        boundScripts = injected.map { it.id },
        dispatchedEvents = events.toList(),
        isNoop = false,
    )
}
```
4. **Verify `BrowserForgeWindowManager.kt` update**
    - Run `cat src/jsMain/kotlin/borg/trikeshed/forge/window/BrowserForgeWindowManager.kt`.
5. **Implement `NodeForgeWindowManager` methods**
    - Use `run_in_bash_session` to write `src/jsMain/kotlin/borg/trikeshed/forge/window/NodeForgeWindowManager.kt`.
    - ```kotlin
package borg.trikeshed.forge.window

import kotlin.time.TimeSource

class NodeForgeWindowManager : ForgeWindowManager {
    private var boundHtml: String = ""
    private val injected = mutableListOf<ScriptSnippet>()
    private val events = mutableListOf<WindowEvent>()

    override fun launch(html: String) {
        val isNode = js("typeof process !== 'undefined' && process.versions != null && process.versions.node != null") as Boolean
        if (isNode) {
            val http = js("require('http')")
            val server = http.createServer { req: dynamic, res: dynamic ->
                res.writeHead(200, js("{'Content-Type': 'text/html'}"))
                res.end(html)
            }
            server.listen(8080)
            println("NodeForgeWindowManager: Serving HTML on http://localhost:8080")
        }
    }

    override fun bind(html: String) {
        boundHtml = html
    }

    override fun injectScript(snippet: ScriptSnippet) {
        injected.add(snippet)
    }

    override fun dispatchEvent(event: WindowEvent) {
        events.add(event)
    }

    override fun captureSnapshot(): WindowSnapshot = WindowSnapshot(
        timestampMillis = TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds,
        dom = boundHtml,
        boundScripts = injected.map { it.id },
        dispatchedEvents = events.toList(),
        isNoop = false,
    )
}
```
6. **Verify `NodeForgeWindowManager.kt` update**
    - Run `cat src/jsMain/kotlin/borg/trikeshed/forge/window/NodeForgeWindowManager.kt`.
7. **Fix `GalleryReactorIntegration.kt`**
    - Fix the `then` type param and `Promise<dynamic>` using `replace_with_git_merge_diff`.
    - ```diff
<<<<<<< SEARCH
    fun sendAction(actionPayload: String): Promise<dynamic> {
        val headers = Headers()
        headers.append("Content-Type", "application/json")

        val init = js("{}")
        init.method = "POST"
        init.headers = headers
        init.body = actionPayload

        return window.fetch(endpointUrl, init as RequestInit)
            .then { response ->
                if (response.ok) {
                    response.json()
                } else {
                    Promise.reject(Exception("Reactor returned \${response.status}"))
                }
            }
    }

    fun subscribeToBlackboard(onEvent: (dynamic) -> Unit) {
        // Fallback polling fetch simulation since full websocket subscription implies long-polling or ws not implemented in NodeFetchReactorEndpoint natively
        window.setInterval({
            if (window.navigator.onLine) {
                val action = """{"verb":"subscribe","nuid":{"capabilityCat":"blackboard","nonceBytes":"","subnet":"global.mesh"},"payload":""}"""
                sendAction(action).then { response ->
                    onEvent(response)
                }.catch { err ->
                    console.log("Subscription poll failed: \$err")
                }
            }
        }, 5000)
    }
=======
    fun sendAction(actionPayload: String): Promise<dynamic> {
        val headers = Headers()
        headers.append("Content-Type", "application/json")

        val init = js("{}")
        init.method = "POST"
        init.headers = headers
        init.body = actionPayload

        return window.fetch(endpointUrl, init as RequestInit)
            .then<dynamic> { response ->
                if (response.ok) {
                    response.json()
                } else {
                    Promise.reject(Exception("Reactor returned \${response.status}"))
                }
            }
    }

    fun subscribeToBlackboard(onEvent: (dynamic) -> Unit) {
        // Fallback polling fetch simulation since full websocket subscription implies long-polling or ws not implemented in NodeFetchReactorEndpoint natively
        window.setInterval({
            if (window.navigator.onLine) {
                val action = """{"verb":"subscribe","nuid":{"capabilityCat":"blackboard","nonceBytes":"","subnet":"global.mesh"},"payload":""}"""
                sendAction(action).then<Unit> { response ->
                    onEvent(response)
                }.catch { err ->
                    console.log("Subscription poll failed: \$err")
                }
            }
        }, 5000)
    }
>>>>>>> REPLACE
```
8. **Fix `SecureIdGenerator.js.kt`**
    - Replace `window.crypto` with `window.asDynamic().crypto`.
    - ```diff
<<<<<<< SEARCH
        val array = Uint8Array(byteLength)
        window.crypto.getRandomValues(array)
        val bytes = ByteArray(byteLength) { i -> array[i] }
=======
        val array = Uint8Array(byteLength)
        window.asDynamic().crypto.getRandomValues(array)
        val bytes = ByteArray(byteLength) { i -> array[i] }
>>>>>>> REPLACE
```
9. **Rename `RunBlockingHelper.kt`**
    - Replace `runBlockingTest` with `runBlocking`.
    - ```diff
<<<<<<< SEARCH
actual fun runBlockingTest(block: suspend () -> Unit): dynamic {
    return GlobalScope.promise { block() }
}
=======
actual fun runBlocking(block: suspend () -> Unit): dynamic {
    return GlobalScope.promise { block() }
}
>>>>>>> REPLACE
```
10. **Test the changes**
    - Execute `./gradlew jvmMainClasses --console=plain`
    - Execute `./gradlew compileKotlinJs --console=plain --continue` and grep for owned path fragments
    - Execute `./gradlew :jvmTest`
11. **Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done.**
12. **Submit the solution.**
