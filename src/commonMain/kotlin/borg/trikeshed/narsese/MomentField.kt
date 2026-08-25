package borg.trikeshed.narsese

import kotlin.concurrent.Volatile
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * MomentField — the bag's NAL-9 layer: the system's beliefs about its OWN
 * attention field, held as attention-weighted first/second moments over the
 * 64-bit coordinate plane. Mental-level operations (introspection, anomaly,
 * self-structure) are queries against this field, not new storage.
 *
 * The mathematics, by name (held pristine until now, built here):
 *  - attention weighting = precision weighting (Feldman & Friston 2010);
 *  - Mahalanobis resonance via Cholesky whitening replaces the s⁴ contrast
 *    hack from first principles: near-constant bits (shared relation/taxonomy
 *    prefixes) stop drowning discriminative ones (Mahalanobis 1936);
 *  - shrinkage keeps the field invertible at cold start (Ledoit–Wolf 2004
 *    is the principled estimator; the λ here is the standard d/(n+d)
 *    heuristic, parameterized so LW can drop in);
 *  - crux axis = Fisher/LDA direction C⁻¹(μ₊−μ₋) (Fisher 1936): the single
 *    direction along which the bag most disagrees with itself — contradiction
 *    as geometry, not bookkeeping;
 *  - cohort anomaly = Hotelling T² (1931): coordinated behavior whose
 *    CORRELATION pattern is alien fires the alarm even when every marginal
 *    looks normal — the legion detector;
 *  - principal concepts = eigenvectors of the field (cyclic Jacobi): the
 *    bag's unnamed axes of variation — emergent ontology candidates.
 *
 * Rebuild is O(n·d²) (n≤4096, d=64 → milliseconds), triggered lazily on a
 * dirty flag; queries against a built field are O(d²) + O(n·d). All state is
 * flat primitive arrays — commonMain autovec, boxing-free, never WAL'd
 * (derived state; the WAL stays a thin ordering log).
 */
class MomentField(val d: Int = 64) {

    // ── field state (guarded by rebuild(); readers take the reference volatile) ──
    val mean = FloatArray(d)
    val meanPos = FloatArray(d)   // attention-weighted mean of positive-polarity beliefs
    val meanNeg = FloatArray(d)   // …and negative-polarity
    private val cov = FloatArray(d * d)      // shrunk covariance
    private val chol = FloatArray(d * d)     // Cholesky factor G: C = G·Gᵀ
    private var whitenedCells = FloatArray(0) // n×d, G⁻¹(x−μ) per live cell
    private var cellAngular = LongArray(0)
    private var cellPri = FloatArray(0)
    private var cellFreq = FloatArray(0)

    @Volatile
    var n: Int = 0
        private set

    @Volatile
    private var built = false

    val isBuilt: Boolean get() = built

    // ── rebuild: one pass over the bag ────────────────────────────────

    fun rebuild(bag: HijackBeliefBag, shrinkLambda: Float = -1f) {
        val coords = ArrayList<Long>(bag.size)
        val pris = ArrayList<Float>(bag.size)
        val freqs = ArrayList<Float>(bag.size)
        bag.forEach { s ->
            coords.add(s.angular)
            pris.add(s.pri.coerceAtLeast(1e-4f))
            freqs.add(Nal.truthOf(s.signal.evidence).frequency)
        }
        val count = coords.size
        n = count
        cellAngular = LongArray(count) { coords[it] }
        cellPri = FloatArray(count) { pris[it] }
        cellFreq = FloatArray(count) { freqs[it] }

        // attention-weighted mean + polarity means
        var wSum = 0f; var wPos = 0f; var wNeg = 0f
        mean.fill(0f); meanPos.fill(0f); meanNeg.fill(0f)
        for (i in 0 until count) {
            val w = cellPri[i]
            val signedPos = cellFreq[i] >= 0.5f
            wSum += w
            if (signedPos) wPos += w else wNeg += w
            val c = cellAngular[i]
            for (b in 0 until d) {
                val x = ((c ushr b) and 1L).toFloat()
                mean[b] += w * x
                if (signedPos) meanPos[b] += w * x else meanNeg[b] += w * x
            }
        }
        if (wSum <= 0f) { built = false; return }
        for (b in 0 until d) {
            mean[b] /= wSum
            if (wPos > 0f) meanPos[b] /= wPos
            if (wNeg > 0f) meanNeg[b] /= wNeg
        }

        // attention-weighted covariance
        cov.fill(0f)
        val xc = FloatArray(d)
        for (i in 0 until count) {
            val w = cellPri[i] / wSum
            val c = cellAngular[i]
            for (b in 0 until d) xc[b] = ((c ushr b) and 1L).toFloat() - mean[b]
            for (r in 0 until d) {
                val wr = w * xc[r]
                val row = r * d
                for (q in 0 until d) cov[row + q] += wr * xc[q]
            }
        }

        // shrinkage toward the scaled identity: C' = (1−λ)C + λ·avgVar·I
        val lambda = if (shrinkLambda in 0f..1f) shrinkLambda else d.toFloat() / (count + d)
        var trace = 0f
        for (b in 0 until d) trace += cov[b * d + b]
        val target = (trace / d).coerceAtLeast(1e-6f)
        for (r in 0 until d) for (q in 0 until d) {
            val i = r * d + q
            cov[i] = (1f - lambda) * cov[i] + (if (r == q) lambda * target else 0f)
        }

        cholesky()

        // whitened cell plane: G⁻¹(x−μ) per cell — queries become O(d²)+O(n·d)
        whitenedCells = FloatArray(count * d)
        val v = FloatArray(d)
        for (i in 0 until count) {
            val c = cellAngular[i]
            for (b in 0 until d) v[b] = ((c ushr b) and 1L).toFloat() - mean[b]
            forwardSolveInto(v, whitenedCells, i * d)
        }
        built = true
    }

    /** C = G·Gᵀ, G lower-triangular; shrinkage guarantees SPD. */
    private fun cholesky() {
        chol.fill(0f)
        for (r in 0 until d) {
            for (q in 0..r) {
                var sum = cov[r * d + q]
                for (k in 0 until q) sum -= chol[r * d + k] * chol[q * d + k]
                if (r == q) {
                    chol[r * d + r] = sqrt(sum.coerceAtLeast(1e-9f))
                } else {
                    chol[r * d + q] = sum / chol[q * d + q]
                }
            }
        }
    }

    /** Solve G·y = v (forward substitution), writing y into [out] at [offset]. */
    private fun forwardSolveInto(v: FloatArray, out: FloatArray, offset: Int) {
        for (r in 0 until d) {
            var sum = v[r]
            val row = r * d
            for (k in 0 until r) sum -= chol[row + k] * out[offset + k]
            out[offset + r] = sum / chol[row + r]
        }
    }

    /** Solve C·w = rhs via G then Gᵀ. */
    private fun solve(rhs: FloatArray): FloatArray {
        val y = FloatArray(d)
        forwardSolveInto(rhs.copyOf(), y, 0)
        val w = FloatArray(d)
        for (r in d - 1 downTo 0) {
            var sum = y[r]
            for (k in r + 1 until d) sum -= chol[k * d + r] * w[k]
            w[r] = sum / chol[r * d + r]
        }
        return w
    }

    // ── queries ───────────────────────────────────────────────────────

    class Peak(val angular: Long, val distance: Float, val activation: Float, val pri: Float, val freq: Float)
    class Resonance(val synonyms: List<Peak>, val antonyms: List<Peak>)

    /**
     * Mahalanobis resonance: whitened distance to every cell, activation
     * exp(−β·d²)·pri, fronts split by evidence polarity. β is the inverse
     * temperature — wire quota pressure here (hot = exploratory, cold = sharp).
     */
    fun resonate(centroid: Long, k: Int = 8, beta: Float = 1f): Resonance {
        if (!built || n == 0) return Resonance(emptyList(), emptyList())
        val q = FloatArray(d)
        for (b in 0 until d) q[b] = ((centroid ushr b) and 1L).toFloat() - mean[b]
        val wq = FloatArray(d)
        forwardSolveInto(q, wq, 0)

        // Peaks are NEAREST-first (whitened distance), priority as tie-break;
        // activation exp(−β·d²/d)·pri rides along as the display/threshold value —
        // ranking by activation lets high-pri distant cells drown true neighbors.
        val syn = ArrayList<Peak>(k + 1)
        val ant = ArrayList<Peak>(k + 1)
        for (i in 0 until n) {
            var d2 = 0f
            val off = i * d
            for (b in 0 until d) {
                val diff = wq[b] - whitenedCells[off + b]
                d2 += diff * diff
            }
            val a = exp(-beta * d2 / d) * cellPri[i]
            val peak = Peak(cellAngular[i], d2, a, cellPri[i], cellFreq[i])
            val side = if (cellFreq[i] >= 0.5f) syn else ant
            var j = side.size
            while (j > 0 && (side[j - 1].distance > d2 ||
                    (side[j - 1].distance == d2 && side[j - 1].pri < peak.pri))
            ) j--
            if (j < k) {
                side.add(j, peak)
                if (side.size > k) side.removeAt(side.size - 1)
            }
        }
        return Resonance(syn, ant)
    }

    /**
     * The crux axis — Fisher direction C⁻¹(μ₊−μ₋): the single direction along
     * which the field most disagrees with itself. [cruxScore] projects one
     * coordinate onto it; large |score| = that belief sits on the controversy.
     */
    fun cruxAxis(): FloatArray {
        if (!built) return FloatArray(d)
        val diff = FloatArray(d) { meanPos[it] - meanNeg[it] }
        val w = solve(diff)
        var norm = 0f
        for (b in 0 until d) norm += w[b] * w[b]
        val inv = if (norm > 0f) 1f / sqrt(norm) else 0f
        for (b in 0 until d) w[b] *= inv
        return w
    }

    fun cruxScore(coord: Long, axis: FloatArray = cruxAxis()): Float {
        var s = 0f
        for (b in 0 until d) s += (((coord ushr b) and 1L).toFloat() - mean[b]) * axis[b]
        return s
    }

    /**
     * Hotelling T² of a cohort against the whole field: alien CORRELATION
     * structure fires even when every marginal is unremarkable. Cohort chosen
     * by predicate over the coordinate (e.g. taxonomy-sig match via
     * AngularCodec.Fields — the pen-verb cohort is the legion alarm).
     */
    fun hotelling(cohort: (Long) -> Boolean): Float {
        if (!built) return 0f
        val cMean = FloatArray(d)
        var cN = 0
        for (i in 0 until n) {
            val c = cellAngular[i]
            if (!cohort(c)) continue
            cN++
            for (b in 0 until d) cMean[b] += ((c ushr b) and 1L).toFloat()
        }
        if (cN == 0) return 0f
        val diff = FloatArray(d) { cMean[it] / cN - mean[it] }
        val w = solve(diff)
        var t2 = 0f
        for (b in 0 until d) t2 += diff[b] * w[b]
        return t2 * cN
    }

    /**
     * Principal concepts: top-k eigenpairs of the field (cyclic Jacobi on the
     * symmetric 64×64). An eigenvector with high variance and no taxonomy name
     * is an UNNAMED supertype — mint it as a GAP upstream.
     */
    fun principalConcepts(k: Int = 4, sweeps: Int = 12): List<Pair<Float, FloatArray>> {
        if (!built) return emptyList()
        val a = cov.copyOf()
        val vec = FloatArray(d * d)
        for (i in 0 until d) vec[i * d + i] = 1f
        repeat(sweeps) {
            for (p in 0 until d - 1) for (q in p + 1 until d) {
                val apq = a[p * d + q]
                if (apq == 0f) continue
                val app = a[p * d + p]; val aqq = a[q * d + q]
                val theta = 0.5f * kotlin.math.atan2(2f * apq, aqq - app)
                val c = kotlin.math.cos(theta); val s = kotlin.math.sin(theta)
                for (i in 0 until d) {
                    val aip = a[i * d + p]; val aiq = a[i * d + q]
                    a[i * d + p] = c * aip - s * aiq
                    a[i * d + q] = s * aip + c * aiq
                }
                for (i in 0 until d) {
                    val api = a[p * d + i]; val aqi = a[q * d + i]
                    a[p * d + i] = c * api - s * aqi
                    a[q * d + i] = s * api + c * aqi
                }
                for (i in 0 until d) {
                    val vip = vec[i * d + p]; val viq = vec[i * d + q]
                    vec[i * d + p] = c * vip - s * viq
                    vec[i * d + q] = s * vip + c * viq
                }
            }
        }
        val pairs = (0 until d).map { i -> a[i * d + i] to FloatArray(d) { r -> vec[r * d + i] } }
        return pairs.sortedByDescending { it.first }.take(k)
    }
}
