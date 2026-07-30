package borg.trikeshed.cursor

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.blackboard.BlackboardSurface
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.json.JsonSupport

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
    val logicFacets: Series<Any?> = emptySeriesOf(),
    val computationFacets: Series<Any?> = emptySeriesOf(),
    val notificationFacets: Series<Any?> = emptySeriesOf(),
    val couplingFacets: Series<Any?> = emptySeriesOf(),
    val layoutHint: LayoutHint = LayoutHint.None,
    val dagCoordinate: DagCoordinate? = null,
    val wtkHint: WtkHint? = null
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
fun convertRequestToCursor(request: Any?): RequestCursor {
    val json = JsonSupport.stringify(request)
    val doc = confixDoc(json)
    return BlackboardSurface.project("request", borg.trikeshed.graph.CausalGraphNodeIndex(), doc, emptyList()).asCursor()
}

/**
 * Convert a response cursor to an HTTP response.
 */
fun convertCursorToResponse(responseCursor: ResponseCursor): Any? = JsonSupport.stringify(responseCursor)

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

