package borg.trikeshed.openapi

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import borg.trikeshed.lib.SeriesBuffer

data class OpenApiCall<I>(
    val callId: CharSequence,
    val input: I,
)

data class OpenApiTruthAction<I, O>(
    val callId: CharSequence,
    val input: I,
    val document: OpenApiRawDocument,
    val tokens: List<OpenApiToken> = emptyList(),
    val analysis: OpenApiGapAnalysis = OpenApiGapAnalysis.EMPTY,
    val output: O,
)

data class OpenApiParsedCall<I>(

    val call: OpenApiCall<I>,
    val rawJson: CharSequence,
    val document: OpenApiRawDocument,
    val tokens: List<OpenApiToken>,
    val analysis: OpenApiGapAnalysis,
) : HasCallId {
    override val callId: CharSequence get() = call.callId
    val input: I get() = call.input
}

data class OpenApiSignalCall<I>(
    val call: OpenApiCall<I>,
    val rawText: CharSequence,
) : HasCallId {
    override val callId: CharSequence get() = call.callId
    val input: I get() = call.input
}

data class OpenApiSignalAction<I, O>(
    val callId: CharSequence,
    val input: I,
    val rawText: CharSequence,
    val output: O,
)

class OpenApiCallFailure(
    val callId: CharSequence,
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

interface HasCallId {
    val callId: CharSequence
}

suspend fun <I, P : HasCallId, R> speculativePipelineBurndown(
    calls: Iterable<OpenApiCall<I>>,
    parallelism: Int = 4,
    parseAction: suspend (OpenApiCall<I>) -> P,
    action: suspend (P) -> R,
): List<R> = coroutineScope {
    val work = calls.toList()
    if (work.isEmpty()) return@coroutineScope emptyList()

    val input = Channel<OpenApiCall<I>>(Channel.BUFFERED)
    val parsed = Channel<P>(Channel.BUFFERED)
    val success = Channel<R>(Channel.BUFFERED)
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
                        parseAction(call)
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

    val actionWorkers = List(max(1, parallelism)) {
        launch {
            for (parsedCall in parsed) {
                val result = runCatching {
                    constructiveSupervisorCall(Dispatchers.Default) {
                        action(parsedCall)
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

    val actionFanIn = launch {
        actionWorkers.forEach { it.join() }
        success.close()
    }

    val results = SeriesBuffer<R>()
    try {
        while (results.size < work.size) {
            select<Unit> {
                failure.onReceive { callFailure ->
                    parseWorkers.forEach { it.cancel() }
                    actionWorkers.forEach { it.cancel() }
                    feed.cancel()
                    parseFanIn.cancel()
                    actionFanIn.cancel()
                    throw callFailure
                }
                success.onReceive { actionResult ->
                    results += actionResult
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
        actionFanIn.cancel()
        parseWorkers.forEach { it.cancel() }
        actionWorkers.forEach { it.cancel() }
    }

    results.toList()
}

suspend fun <I, O> speculativeGapBurndown(
    calls: Iterable<OpenApiCall<I>>,
    parallelism: Int = 4,
    parser: suspend (I) -> String,
    truthAction: suspend (OpenApiParsedCall<I>) -> O,
): List<OpenApiTruthAction<I, O>> = speculativePipelineBurndown(
    calls = calls,
    parallelism = parallelism,
    parseAction = { call ->
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
    },
    action = { parsedCall ->
        OpenApiTruthAction(
            callId = parsedCall.callId,
            input = parsedCall.input,
            document = parsedCall.document,
            tokens = parsedCall.tokens,
            analysis = parsedCall.analysis,
            output = truthAction(parsedCall),
        )
    }
)

suspend fun <I, O> speculativeParseBurndown(
    calls: ReceiveChannel<OpenApiCall<I>>,
    parallelism: Int = 4,
    parser: suspend (I) -> String,
    truthAction: suspend (OpenApiCall<I>, OpenApiRawDocument) -> O,
): List<OpenApiTruthAction<I, O>> {
    val buffered = SeriesBuffer<OpenApiCall<I>>()
    for (call in calls) buffered += call
    return speculativeParseBurndown(buffered.toList(), parallelism, parser, truthAction)
}

suspend fun <I, O> speculativeGapBurndown(
    calls: ReceiveChannel<OpenApiCall<I>>,
    parallelism: Int = 4,
    parser: suspend (I) -> String,
    truthAction: suspend (OpenApiParsedCall<I>) -> O,
): List<OpenApiTruthAction<I, O>> {
    val buffered = SeriesBuffer<OpenApiCall<I>>()
    for (call in calls) buffered += call
    return speculativeGapBurndown(buffered.toList(), parallelism, parser, truthAction)
}

suspend fun <I, O> speculativeSignalBurndown(
    calls: Iterable<OpenApiCall<I>>,
    parallelism: Int = 4,
    parser: suspend (I) -> String,
    signalAction: suspend (OpenApiSignalCall<I>) -> O,
): List<OpenApiSignalAction<I, O>> = speculativePipelineBurndown(
    calls = calls,
    parallelism = parallelism,
    parseAction = { call ->
        val rawText = parser(call.input)
        OpenApiSignalCall(call = call, rawText = rawText)
    },
    action = { parsedCall ->
        OpenApiSignalAction(
            callId = parsedCall.callId,
            input = parsedCall.input,
            rawText = parsedCall.rawText,
            output = signalAction(parsedCall),
        )
    }
)

suspend fun <I, O> speculativeSignalBurndown(
    calls: ReceiveChannel<OpenApiCall<I>>,
    parallelism: Int = 4,
    parser: suspend (I) -> String,
    signalAction: suspend (OpenApiSignalCall<I>) -> O,
): List<OpenApiSignalAction<I, O>> {
    val buffered = SeriesBuffer<OpenApiCall<I>>()
    for (call in calls) buffered += call
    return speculativeSignalBurndown(buffered.toList(), parallelism, parser, signalAction)
}
