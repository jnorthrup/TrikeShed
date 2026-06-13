# How to Launch the Couch Daemon (ReactorSupervisor)

The Couch daemon is built around `ReactorSupervisor` — a coroutine-supervised reactor that owns the lifecycle of protocol recognizers, parse scopes, and session dispatches. It uses a clean palette: `CREATED → OPEN → ACTIVE → DRAINING → CLOSED`.

## Prerequisites

- JDK 25 (configured via `jvmToolchain(25)` in build)
- GraalVM 24.1.1 (for JS view server)
- All TrikeShed libs in the reactor chain: `couch`, `miniduck`, `tiny-btrfs`, `patl`, `tls`, `quic`, `kursive`, `htx-client`, `ngsctp`, `concurrency`, `uring`, `ipfs`

## Quick Start (from repo root)

```bash
# 1. Build the jvmMain classes for couch and its dependencies
./gradlew :libs:couch:compileKotlinJvm :libs:couch:jvmJar --no-configuration-cache

# 2. Locate the runtime classpath (includes all transitive libs)
#    The `quickValidate` task in couch build shows the pattern:
RUNTIME_CP=$(./gradlew :libs:couch:jvmRuntimeClasspath --no-configuration-cache -q 2>/dev/null | tr ':' '\n' | grep -v '^$' | tr '\n' ':')

# 3. Run the reactor with a minimal bootstrap
#    Replace <main-class> with your entry point (see examples below)
java -cp "$RUNTIME_CP" -Xmx1g <main-class>
```

## Minimal Daemon Entry Point

Create a file `CouchDaemonMain.kt` in your project or use inline script:

```kotlin
// CouchDaemonMain.kt
package borg.trikeshed.couch.daemon

import borg.trikeshed.couch.runtime.Reactor
import borg.trikeshed.couch.runtime.CouchRuntime
import borg.trikeshed.couch.userspace.nio.ReactorSupervisor
import borg.trikeshed.couch.userspace.network.ProtocolRecognizer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.coroutines.CoroutineContext

fun main(args: Array<String>) = runBlocking {
    val realm = args.firstOrNull() ?: "default"
    
    // 1. Create ReactorSupervisor (the daemon core)
    val reactor = ReactorSupervisor(realm)
    
    // 2. Install context elements (codecs, transports, stores)
    //    Example: reactor.withKey(SomeKey, SomeElement)
    
    // 3. Open → Activate lifecycle
    reactor.open()
    reactor.activate()
    println("[CouchDaemon] Reactor ACTIVE on realm='$realm'")
    
    // 4. Launch protocol recognizer branch
    val htxChannel = Channel<HtxBlock>(capacity = 1024)
    reactor.launchBranch("protocol-recognizer", htxChannel) {
        ProtocolRecognizer(htxChannel, realm).run(reactor)
    }
    
    // 5. Keep alive until shutdown signal
    println("[CouchDaemon] Press Ctrl-C to drain and close")
    awaitShutdownSignal()
    
    // 6. Graceful drain/close
    reactor.drain()
    reactor.close()
    println("[CouchDaemon] Reactor CLOSED")
}

private suspend fun awaitShutdownSignal(): Unit = kotlinx.coroutines.selects.select<Unit> {
    // Wait for SIGTERM/SIGINT or a shutdown channel
    kotlinx.coroutines.awaitCancellation()
}
```

## Run the Daemon

```bash
# Compile your daemon main
./gradlew :libs:couch:compileKotlinJvm --no-configuration-cache

# Run directly via Gradle (recommended for dev)
./gradlew :libs:couch:run --args="myrealm" --no-configuration-cache

# Or run the jar with explicit classpath
java -cp "$(./gradlew :libs:couch:jvmRuntimeClasspath --no-configuration-cache -q 2>/dev/null | tr ' ' ':')" \
     -Xmx1g \
     borg.trikeshed.couch.daemon.CouchDaemonMainKt myrealm
```

## Adding the `run` Task to couch/build.gradle.kts

```kotlin
// In libs/couch/build.gradle.kts
tasks.register("runDaemon", JavaExec) {
    group = "daemon"
    description = "Run Couch ReactorSupervisor daemon"
    dependsOn("compileKotlinJvm")
    
    val runtimeConfig = configurations.findByName("jvmRuntimeClasspath")
        ?: configurations.findByName("jvmMainRuntimeClasspath")
        ?: error("No jvm runtime classpath found")
    classpath = runtimeConfig
    
    mainClass = "borg.trikeshed.couch.daemon.CouchDaemonMainKt"
    jvmArgs = ["-Xmx1g", "-XX:+UseZGC"]
    args("default") // realm name
}
```

Then:
```bash
./gradlew :libs:couch:runDaemon --no-configuration-cache
```

## Using CouchRuntime (Higher-Level)

If you want the HTX transport + CouchDB protocol stack:

```kotlin
import borg.trikeshed.couch.runtime.Reactor
import borg.trikeshed.couch.runtime.CouchRuntime
import borg.trikeshed.couch.transport.htx.HtxBackedCouchTransport

fun main() = runBlocking {
    val reactor = Reactor("couch-realm")
    val runtime = CouchRuntime(reactor)
    
    reactor.open()
    reactor.activate()
    
    // runtime.transport is now an HtxBackedCouchTransport
    // ready to accept connections via NIO/NG-SCTP
    
    awaitShutdownSignal()
    reactor.drain()
    reactor.close()
}
```

## Daemon Lifecycle Summary

| State | Method | Description |
|-------|--------|-------------|
| CREATED | (constructor) | SupervisorJob created, no branches |
| OPEN | `open()` | Ready to accept branch launches |
| ACTIVE | `activate()` | Branches running, processing messages |
| DRAINING | `drain()` | Stop new work, complete in-flight |
| CLOSED | `close()` | All children sealed, supervisor complete |

## Key Classes

- `ReactorSupervisor` — Core daemon (`libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/userspace/nio/ReactorSupervisor.kt`)
- `Reactor` — Thin facade (`libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/runtime/Reactor.kt`)
- `CouchRuntime` — Adds HTX transport (`libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/runtime/CouchRuntime.kt`)
- `ProtocolRecognizer` — Branch template (`libs/couch/src/jvmMain/kotlin/borg/trikeshed/couch/userspace/network/ProtocolRecognizer.kt`)

## Testing the Daemon

```bash
# Run the lifecycle test (validates state machine)
./gradlew :libs:couch:jvmTest --tests "borg.trikeshed.couch.userspace.nio.ReactorSupervisorLifecycleTest" --no-configuration-cache

# Run the full ReactorSupervisor test suite
./gradlew :libs:couch:jvmTest --tests "borg.trikeshed.couch.userspace.nio.ReactorSupervisorTest" --no-configuration-cache
```