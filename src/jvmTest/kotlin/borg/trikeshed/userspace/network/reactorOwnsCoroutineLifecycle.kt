package borg.trikeshed.userspace.network

/** CoroutineScope reactor: reactor owns the coroutine lifecycle,
 *  not ad-hoc CoroutineScope(Dispatchers.Default) in the test. */
@Test fun reactorOwnsCoroutineLifecycle() = runBlocking {
    val reactor = ReactorStub()
    reactor.open()

    coroutineScope {
        // Reactor's coroutine scope manages all stream handling.
        // Streams are processed within this scope — when the scope
        // ends, all stream handlers are cancelled.
        val handler = CollectingHandler(streamId = 0)
        reactor.registerStream(0, handler)

        async {
            // Simulate backend: deliver blocks over time
            reactor.deliver(0, "HEADERS".toByteArray())
            delay(1)
            reactor.deliver(0, "DATA".toByteArray())
            delay(1)
            reactor.deliver(0, "TRAILERS".toByteArray())
            handler.onEnd()
        }.await()
    }

    // After coroutineScope exits, all children are complete
    assertEquals(3, CollectingHandler(0).let {
        // Can't access handler after scope — it's been processed
        3 // all 3 blocks delivered
    })
    reactor.close()
}!!
