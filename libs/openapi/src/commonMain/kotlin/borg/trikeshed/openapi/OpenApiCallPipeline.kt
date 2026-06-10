package borg.trikeshed.openapi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.coroutines.CoroutineContext
import kotlin.math.max

data class OpenApiCall<I>(
    val callId: String,
    val input: I,
)

data class OpenApiTruthAction<I, O>(
    val callId: String,
    val input: I,
    val document: OpenApiRawDocument,
    val tokens: List<OpenApiToken> = emptyList(),
    val analysis: OpenApiGapAnalysis = OpenApiGapAnalysis.EMPTY,
    val output: O,
)

data class OpenApiParsedCall<I>(
    val call: OpenApiCall<I>,
    val rawJson: String,
    val document: OpenApiRawDocument,
    val tokens: List<OpenApiToken>,
    val analysis: OpenApiGapAnalysis,
) {
    val callId: String get() = call.callId
    val input: I get() = call.input
}

data class OpenApiSignalCall<I>(
    val call: OpenApiCall<I>,
    val rawText: String,
) {
    val callId: String get() = call.callId
    val input: I get() = call.input
}

data class OpenApiSignalAction<I, O>(
    val callId: String,
    val input: I,
    val rawText: String,
    val output: O,
)

class OpenApiCallFailure(
    val callId: String,
    cause: Throwable,
) : RuntimeException("OpenAPI call '$callId' failed", cause)

suspend fun <T> constructiveSupervisorCall(
    context: CoroutineContext,
    block: suspend CoroutineScope.() -> T,
): T = coroutineScope {
    val parentJob = coroutineContext[Job]
    val supervisor = SupervisorJob(parentJob)
    val callScope = CoroutineScope(context + supervisor)
    try {
        callScope.async { block(callScope) }.await()
    } finally {
        supervisor.cancel()
    }
}

suspend fun <I, O> speculativeParseBurndown(
    calls: Iterable<OpenApiCall<I>>,
    parallelism: Int = 4,
    parser: suspend (I) -> String,
    truthAction: suspend (OpenApiCall<I>, OpenApiRawDocument) -> O,
): List<OpenApiTruthAction<I, O>> =
    speculativeGapBurndown(
        calls = calls,
        parallelism = parallelism,
        parser = parser,
        truthAction = { parsed -> truthAction(parsed.call, parsed.document) },
    )

suspend fun <I, O> speculativeGapBurndown(
    calls: Iterable<OpenApiCall<I>>,
    parallelism: Int = 4,
    parser: suspend (I) -> String,
    truthAction: suspend (OpenApiParsedCall<I>) -> O,
): List<OpenApiTruthAction<I, O>> = coroutineScope {
    val work = calls.toList()
    if (work.isEmpty()) return@coroutineScope emptyList()

    val input = Channel<OpenApiCall<I>>(Channel.BUFFERED)
    val parsed = Channel<OpenApiParsedCall<I>>(Channel.BUFFERED)
    val success = Channel<OpenApiTruthAction<I, O>>(Channel.BUFFERED)
    val failure = Channel<OpenApiCallFailure>(1)

    val feed = launch {
        for (call in work) {
            input.send(call)
        }
        input.close()
    }

    val parseWorkers = List(max(1, parallelism)) {
        launch {
            for (call in input) {
                val result = runCatching {
                    constructiveSupervisorCall(Dispatchers.Default) {
                        val rawJson = parser(call.input)
                        val document = OpenApiRawParser.parse(rawJson)
                        val analysis = document.gapAnalysis()
                        OpenApiParsedCall(
                            call = call,
                            rawJson = rawJson,
                            document = document,
                            tokens = analysis.tokens,
                            analysis = analysis,
                        )
                    }
                }

                result.onSuccess {
                    parsed.send(it)
                }.onFailure { cause ->
                    if (cause is CancellationException) throw cause
                    failure.trySend(OpenApiCallFailure(call.callId, cause))
                    return@launch
                }
            }
        }
    }

    val parseFanIn = launch {
        parseWorkers.forEach { it.join() }
        parsed.close()
    }

    val truthWorkers = List(max(1, parallelism)) {
        launch {
            for (parsedCall in parsed) {
                val result = runCatching {
                    constructiveSupervisorCall(Dispatchers.Default) {
                        OpenApiTruthAction(
                            callId = parsedCall.callId,
                            input = parsedCall.input,
                            document = parsedCall.document,
                            tokens = parsedCall.tokens,
                            analysis = parsedCall.analysis,
                            output = truthAction(parsedCall),
                        )
                    }
                }

                result.onSuccess {
                    success.send(it)
                }.onFailure { cause ->
                    if (cause is CancellationException) throw cause
                    failure.trySend(OpenApiCallFailure(parsedCall.callId, cause))
                    return@launch
                }
            }
        }
    }

    val truthFanIn = launch {
        truthWorkers.forEach { it.join() }
        success.close()
    }

    val results = mutableListOf<OpenApiTruthAction<I, O>>()
    try {
        while (results.size < work.size) {
            select<Unit> {
                failure.onReceive { callFailure ->
                    parseWorkers.forEach { it.cancel() }
                    truthWorkers.forEach { it.cancel() }
                    feed.cancel()
                    parseFanIn.cancel()
                    truthFanIn.cancel()
                    throw callFailure
                }
                success.onReceive { action ->
                    results += action
                }
            }
        }
    } finally {
        input.cancel()
        parsed.cancel()
        success.cancel()
        failure.cancel()
        feed.cancel()
        parseFanIn.cancel()
        truthFanIn.cancel()
        parseWorkers.forEach { it.cancel() }
        truthWorkers.forEach { it.cancel() }
    }

    results
}

suspend fun <I, O> speculativeParseBurndown(
    calls: ReceiveChannel<OpenApiCall<I>>,
    parallelism: Int = 4,
    parser: suspend (I) -> String,
    truthAction: suspend (OpenApiCall<I>, OpenApiRawDocument) -> O,
): List<OpenApiTruthAction<I, O>> {
    val buffered = mutableListOf<OpenApiCall<I>>()
    for (call in calls) buffered += call
    return speculativeParseBurndown(buffered, parallelism, parser, truthAction)
}

suspend fun <I, O> speculativeGapBurndown(
    calls: ReceiveChannel<OpenApiCall<I>>,
    parallelism: Int = 4,
    parser: suspend (I) -> String,
    truthAction: suspend (OpenApiParsedCall<I>) -> O,
): List<OpenApiTruthAction<I, O>> {
    val buffered = mutableListOf<OpenApiCall<I>>()
    for (call in calls) buffered += call
    return speculativeGapBurndown(buffered, parallelism, parser, truthAction)
}

suspend fun <I, O> speculativeSignalBurndown(
    calls: Iterable<OpenApiCall<I>>,
    parallelism: Int = 4,
    parser: suspend (I) -> String,
    signalAction: suspend (OpenApiSignalCall<I>) -> O,
): List<OpenApiSignalAction<I, O>> = coroutineScope {
    val work = calls.toList()
    if (work.isEmpty()) return@coroutineScope emptyList()

    val input = Channel<OpenApiCall<I>>(Channel.BUFFERED)
    val parsed = Channel<OpenApiSignalCall<I>>(Channel.BUFFERED)
    val success = Channel<OpenApiSignalAction<I, O>>(Channel.BUFFERED)
    val failure = Channel<OpenApiCallFailure>(1)

    val feed = launch {
        for (call in work) {
            input.send(call)
        }
        input.close()
    }

    val parseWorkers = List(max(1, parallelism)) {
        launch {
            for (call in input) {
                val result = runCatching {
                    constructiveSupervisorCall(Dispatchers.Default) {
                        val rawText = parser(call.input)
                        OpenApiSignalCall(call = call, rawText = rawText)
                    }
                }

                result.onSuccess {
                    parsed.send(it)
                }.onFailure { cause ->
                    if (cause is CancellationException) throw cause
                    failure.trySend(OpenApiCallFailure(call.callId, cause))
                    return@launch
                }
            }
        }
    }

    val parseFanIn = launch {
        parseWorkers.forEach { it.join() }
        parsed.close()
    }

    val signalWorkers = List(max(1, parallelism)) {
        launch {
            for (parsedCall in parsed) {
                val result = runCatching {
                    constructiveSupervisorCall(Dispatchers.Default) {
                        OpenApiSignalAction(
                            callId = parsedCall.callId,
                            input = parsedCall.input,
                            rawText = parsedCall.rawText,
                            output = signalAction(parsedCall),
                        )
                    }
                }

                result.onSuccess {
                    success.send(it)
                }.onFailure { cause ->
                    if (cause is CancellationException) throw cause
                    failure.trySend(OpenApiCallFailure(parsedCall.callId, cause))
                    return@launch
                }
            }
        }
    }

    val signalFanIn = launch {
        signalWorkers.forEach { it.join() }
        success.close()
    }

    val results = mutableListOf<OpenApiSignalAction<I, O>>()
    try {
        while (results.size < work.size) {
            select<Unit> {
                failure.onReceive { callFailure ->
                    parseWorkers.forEach { it.cancel() }
                    signalWorkers.forEach { it.cancel() }
                    feed.cancel()
                    parseFanIn.cancel()
                    signalFanIn.cancel()
                    throw callFailure
                }
                success.onReceive { action ->
                    results += action
                }
            }
        }
    } finally {
        input.cancel()
        parsed.cancel()
        success.cancel()
        failure.cancel()
        feed.cancel()
        parseFanIn.cancel()
        signalFanIn.cancel()
        parseWorkers.forEach { it.cancel() }
        signalWorkers.forEach { it.cancel() }
    }

    results
}

suspend fun <I, O> speculativeSignalBurndown(
    calls: ReceiveChannel<OpenApiCall<I>>,
    parallelism: Int = 4,
    parser: suspend (I) -> String,
    signalAction: suspend (OpenApiSignalCall<I>) -> O,
): List<OpenApiSignalAction<I, O>> {
    val buffered = mutableListOf<OpenApiCall<I>>()
    for (call in calls) buffered += call
    return speculativeSignalBurndown(buffered, parallelism, parser, signalAction)
}
