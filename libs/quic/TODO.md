# TODO: QUIC Integration

1. Fix compilation errors in `libs/quic/build.gradle.kts`. Currently, KMP resolution fails to find root project dependencies (`AsyncContextElement` etc.). Need to configure the build file correctly (potentially modifying the macro `gradle/macros/trikeshed-lib.gradle` or ensuring the targets match up).
2. Wire up `QuicheRunner` into `QuicElement` so that when `openQuicElement()` is called, the CCEK properly spawns the `quiche-server` binary and manages its lifecycle.
3. Hook `QuicElement` with the larger TrikeShed Reactor, bridging the `quiche-server` process standard I/O (via `ProcessOperations`) into `UserspaceRing` sockets or NIO streams.
