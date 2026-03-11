package borg.trikeshed.manifold

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * Semantic coordinates stay indexed and lazy.
 * Dense lowered views materialize separately so one type does not pretend to do both jobs.
 */
data class Coordinates<T>(val axes: Series<T>) {
    val dimension: Int get() = axes.size

    operator fun get(axis: Int): T = axes[axis]

    fun lowered(): DenseCoordinates<T> = DenseCoordinates(List(dimension) { axis: Int -> axes[axis] })
}

/**
 * Lowered/materialized coordinate storage.
 * This is intentionally not a Series so semantic and dense layers remain distinct.
 */
data class DenseCoordinates<T>(val axes: List<T>) {
    val dimension: Int get() = axes.size

    operator fun get(axis: Int): T = axes[axis]

    fun semantic(): Coordinates<T> = Coordinates(dimension j { axis: Int -> axes[axis] })
}

fun <T> coordinatesOf(vararg axes: T): Coordinates<T> =
    Coordinates(axes.size j { axis: Int -> axes[axis] })

fun <T> denseCoordinatesOf(vararg axes: T): DenseCoordinates<T> =
    DenseCoordinates(axes.toList())

data class Chart<C, P>(
    val name: String,
    val dimension: Int,
    val contains: (P) -> Boolean = { true },
    val project: (P) -> Coordinates<C>?,
    val embed: (Coordinates<C>) -> P,
) {
    fun locate(point: P): Coordinates<C>? = if (contains(point)) project(point) else null

    fun point(coordinates: Coordinates<C>): P {
        require(coordinates.dimension == dimension) {
            "chart '$name' expects $dimension coordinates but got ${coordinates.dimension}"
        }
        return embed(coordinates)
    }

    fun lowered(point: P): DenseCoordinates<C>? = locate(point)?.lowered()
}

typealias ChartedPoint<C, P> = Join<Chart<C, P>, Coordinates<C>>

class Atlas<C, P>(val charts: Series<Chart<C, P>>) {
    val size: Int get() = charts.size

    val dimension: Int = if (charts.size == 0) 0 else charts[0].dimension

    init {
        for (i in 0 until charts.size) {
            require(charts[i].dimension == dimension) {
                "atlas chart '${charts[i].name}' dimension ${charts[i].dimension} disagrees with $dimension"
            }
        }
    }

    fun chart(name: String): Chart<C, P>? {
        for (i in 0 until charts.size) {
            val chart = charts[i]
            if (chart.name == name) return chart
        }
        return null
    }

    fun locate(point: P): ChartedPoint<C, P>? {
        for (i in 0 until charts.size) {
            val chart = charts[i]
            val coordinates = chart.locate(point) ?: continue
            return chart j coordinates
        }
        return null
    }
}

data class Manifold<C, P>(val atlas: Atlas<C, P>) {
    val dimension: Int get() = atlas.dimension

    fun locate(point: P): ChartedPoint<C, P>? = atlas.locate(point)

    fun point(chartName: String, coordinates: Coordinates<C>): P? =
        atlas.chart(chartName)?.point(coordinates)

    fun transition(fromChartName: String, toChartName: String, coordinates: Coordinates<C>): Coordinates<C>? {
        val from = atlas.chart(fromChartName) ?: return null
        val to = atlas.chart(toChartName) ?: return null
        return to.locate(from.point(coordinates))
    }
}
