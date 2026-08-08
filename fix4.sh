#!/bin/bash
# HtxKey is the singleton key for HtxElement. CoroutineContext acts like a map, so you can only have one HtxElement per context under HtxKey.
# To use multiple HtxElements, we can't just put them all in the context.
# We have to explicitly pass the elements to launch, or pass them as parameters to startReactiveCycle.
# Actually, the issue states: "Because the underlying HtxElement (backed by JvmTlsCodecBackend) uses a single SSLEngine per endpoint, it is not thread-safe for concurrent exchanges."
# Wait, "open N HtxElement instances (one per concurrent operation class)".
# And we can pass these to FlywheelDriver or just create them in FlywheelDriver?
# FlywheelDriver.startReactiveCycle gets the htxElement from coroutineContext[HtxKey].
# If we change startReactiveCycle to accept (htxDrain: HtxElement, htxAnswer: HtxElement, htxDispatch: HtxElement), then we can use those.
