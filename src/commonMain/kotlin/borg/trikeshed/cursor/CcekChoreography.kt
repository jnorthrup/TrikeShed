package borg.trikeshed.cursor

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Join
import borg.trikeshed.userspace.nio.spi.NioSupervisor

// ==================== CCEK CHOREOGRAPHY CONTRACTS ====================
//
// These contracts define the data-flow chain from data sources through cursor
// facets to user-signalling events. They are TODO-only stubs until
// implementation is explicitly requested.
//
// Data-flow chain:
//   data source -> Cursor -> FacetedCursor -> LCNC facet grouping -> user-signal event -> Forge/Kanban overlay

// ==================== LCNC FACET HANDLES ====================

/**
 * Layout hint facet — describes how a cursor value should be rendered/laid out.
 * Used by WTK and Forge overlay systems.
 */
sealed class LayoutHint {
    /** Horizontal layout preference */
    data object Horizontal : LayoutHint()

    /** Vertical layout preference */
    data object Vertical : LayoutHint()

    /** Grid/flex layout */
    data object Grid : LayoutHint()

    /** Stack layout (vertical with wrapping) */
    data object Stack : LayoutHint()

    /** No layout preference */
    data object None : LayoutHint()
}

/**
 * DAG coordinate facet — describes cursor value's position in the computation DAG.
 */
data class DagCoordinate(
    val depth: Int,
    val position: Int,
    val parent: DagCoordinate? = null
)

/**
 * WTK hint facet — describes which WTK (Widget Toolkit) component should render this value.
 */
sealed class WtkHint {
    /** Text label component */
    data object Label : WtkHint()

    /** Button component */
    data object Button : WtkHint()

    /** Input field component */
    data object Input : WtkHint()

    /** Slider component */
    data object Slider : WtkHint()

    /** Table/grid component */
    data object Table : WtkHint()

    /** Chart component */
    data object Chart : WtkHint()

    /** Image component */
    data object Image : WtkHint()

    /** Custom WTK component by name */
    data class Custom(val name: String) : WtkHint()
}

/**
 * LCNC facet group — groups cursor facets for batch processing.
 */
data class LcncFacetGroup(
    val logicFacets: Series<Any?> = emptySeries(),
    val computationFacets: Series<Any?> = emptySeries(),
    val notificationFacets: Series<Any?> = emptySeries(),
    val couplingFacets: Series<Any?> = emptySeries(),
    val layoutHint: LayoutHint = LayoutHint.None,
    val dagCoordinate: DagCoordinate? = null,
    val wtkHint: WtkHint? = null
)

// ==================== CCEK CONTRACT: CURSOR TO USER-SIGNAL ====================

/**
 * CCEK choreography entry point for command-line invocation.
 *
 * Contract:
 *   main(args)
 *   -> default SupervisorJob
 *   -> NioSupervisor
 *   -> LCNC cursor facets
 *   -> user-signalling events
 */
fun ccekCommandLineChoreography(
    args: Array<String>,
    supervisorJob: Any? = null,
    nioSupervisor: NioSupervisor? = null
): Series<Any?> = TODO(
    "CCEK command-line choreography: args -> SupervisorJob -> NioSupervisor -> LCNC facets -> user-signals"
)

/**
 * CCEK choreography entry point for generated API invocation.
 *
 * Contract:
 *   OpenAPI operation
 *   -> generated request cursor
 *   -> default SupervisorJob
 *   -> NioSupervisor
 *   -> LCNC cursor facets
 *   -> user-signalling events
 */
fun ccekGeneratedApiChoreography(
    operationId: String,
    requestCursor: Cursor,
    supervisorJob: Any? = null,
    nioSupervisor: NioSupervisor? = null
): Series<Any?> = TODO(
    "CCEK generated API choreography: operation -> requestCursor -> SupervisorJob -> NioSupervisor -> LCNC facets -> user-signals"
)

/**
 * Lift a cursor with facets into a user-signalling event.
 *
 * This is the core contract that connects LCNC cursor facets to the user-signals system.
 */
fun liftCursorFacetsToUserSignal(
    facetedCursor: Cursor, // TODO: FacetedCursor
    facetGroup: LcncFacetGroup
): Series<Any?> = TODO(
    "FacetedCursor + LcncFacetGroup -> user-signal event series"
)

/**
 * Lift a cursor into user-signals with automatic facet inference.
 */
fun cursorToUserSignals(cursor: Cursor): Series<Any?> = TODO(
    "Cursor (auto-facetted) -> user-signal event series"
)

// ==================== CCEK CONTRACT: REQUEST CURSOR ====================

/**
 * Request cursor — a cursor that represents an HTTP/API request.
 */
typealias RequestCursor = Cursor

/**
 * Response cursor — a cursor that represents an HTTP/API response.
 */
typealias ResponseCursor = Cursor

/**
 * Convert a generated request to a request cursor.
 */
fun convertRequestToCursor(request: Any?): RequestCursor = TODO(
    "HTTP request / generated request -> RequestCursor"
)

/**
 * Convert a response cursor to an HTTP response.
 */
fun convertCursorToResponse(responseCursor: ResponseCursor): Any? = TODO(
    "ResponseCursor -> HTTP response"
)

// ==================== SUPERVISOR SCAFFOLD CONTRACT ====================

/**
 * Scope provided by trikeShedMain.
 * Platform-specific actual implementations live in jvmMain.
 */
interface TrikeShedScope {
    /** Command-line arguments */
    val args: Array<String>

    /** The SupervisorJob for structured concurrency */
    val supervisorJob: Any

    /** The NioSupervisor for network I/O */
    val nioSupervisor: NioSupervisor

    /** Coroutine context composition */
    val coroutineContext: Any
}

/**
 * Open the default NioSupervisor from CoroutineContext.
 */
fun kotlin.coroutines.CoroutineContext.nioSupervisor(): NioSupervisor? =
    get(NioSupervisor.Key)

/**
 * Get or create the NioSupervisor from this context.
 */
fun kotlin.coroutines.CoroutineContext.getOrCreateNioSupervisor(): NioSupervisor {
    val existing = nioSupervisor()
    if (existing != null) return existing
    return NioSupervisor().also { supervisor ->
        // Note: caller should add to context and open
    }
}

// ==================== HELPER FUNCTIONS ====================

private fun <T> emptySeries(): Series<T> =
    object : Join<Int, (Int) -> T> {
        override val a: Int get() = 0
        override val b: (Int) -> T get() = { _: Int -> throw IndexOutOfBoundsException("Empty series") }
    }