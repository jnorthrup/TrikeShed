package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*

/** Routable model id and observed model revision; null means the revision is unknown. */
typealias ProficiencyModel = Join<String, String?>

/** Harness revision and task kind. */
typealias ProficiencyTask = Twin<String>

enum class ModelCorrectness { UNKNOWN, ACCURATE, INACCURATE }
enum class ModelDisposition { UNSELECTED, SELECTED, EXPERIMENT_REJECTED }

object ModelGradeBasis {
    const val EVIDENCE = "plane-evidence-v1"
    const val CORRECTNESS = "human-correctness-v1"
}

/** One persisted assessment; sequence is the receipt store's commit order. */
data class ModelGrade(
    val attemptId: String,
    val model: ProficiencyModel,
    val task: ProficiencyTask,
    val sequence: Long,
    val basis: String,
    val score: Double?,
    val correctness: ModelCorrectness,
    val disposition: ModelDisposition,
) {
    init {
        require(attemptId.isNotBlank() && model.a.isNotBlank())
        require(task.a.isNotBlank() && task.b.isNotBlank() && basis.isNotBlank())
        require(score == null || score.isFinite() && score in 0.0..1.0)
    }
}

/** Decode grade fields only; transport `ok`, board columns and selection are not scores. */
fun modelGrade(fields: Map<*, *>, sequence: Long): ModelGrade? {
    val attempt = (fields["attemptId"] as? String)?.takeIf { it.isNotBlank() } ?: return null
    val id = (fields["model"] as? String)?.takeIf { it.isNotBlank() } ?: return null
    val harness = (fields["harness"] as? String)?.takeIf { it.isNotBlank() } ?: return null
    val task = (fields["taskKind"] as? String)?.takeIf { it.isNotBlank() } ?: return null
    val basis = (fields["basis"] as? String)?.takeIf { it.isNotBlank() } ?: return null
    val version = when (val value = fields["modelVersion"]) {
        null -> null
        is String -> value.takeIf { it.isNotBlank() }
        else -> return null
    }
    val score = when (val value = fields["score"]) {
        null -> null
        is Number -> value.toDouble().takeIf { it.isFinite() && it in 0.0..1.0 } ?: return null
        else -> return null
    }
    val correctness = ModelCorrectness.entries.firstOrNull { it.name == fields["correctness"] } ?: return null
    val disposition = ModelDisposition.entries.firstOrNull { it.name == fields["disposition"] } ?: return null
    return ModelGrade(attempt, id j version, harness j task, sequence, basis, score, correctness, disposition)
}

/**
 * Rank exact model revisions by mean measured grade for one task and basis.
 * Latest assessment per attempt and basis supersedes earlier assessments; a
 * replayed receipt contributes once. No evidence has the neutral prior 0.5,
 * so a measured failure ranks below an unmeasured candidate. Equal scores keep
 * input order. Disposition never changes accuracy or evidence compliance.
 *
 * Compose EVIDENCE first, then CORRECTNESS to give human accuracy precedence.
 * Correctness scores derive only from explicit human assessments, not `score`
 * or automatic evidence checks. UNKNOWN withdraws that attempt's assessment.
 */
fun <C> rankModelProficiency(
    candidates: Series<C>,
    grades: Series<ModelGrade>,
    task: ProficiencyTask,
    basis: String,
    model: (C) -> ProficiencyModel,
): Series<C> {
    val latest = HashMap<String, ModelGrade>()
    for (grade in grades.view) {
        if (grade.basis != basis) continue
        val previous = latest[grade.attemptId]
        if (previous == null || grade.sequence > previous.sequence) latest[grade.attemptId] = grade
    }
    val measured = HashMap<Pair<String, String?>, Join<Int, Double>>()
    for (grade in latest.values) {
        if (grade.task.a != task.a || grade.task.b != task.b) continue
        val score = if (basis == ModelGradeBasis.CORRECTNESS) when (grade.correctness) {
            ModelCorrectness.ACCURATE -> 1.0
            ModelCorrectness.INACCURATE -> 0.0
            ModelCorrectness.UNKNOWN -> null
        } else grade.score
        if (score == null) continue
        val identity = grade.model.pair
        val previous = measured[identity]
        measured[identity] = ((previous?.a ?: 0) + 1) j ((previous?.b ?: 0.0) + score)
    }
    val scores = DoubleArray(candidates.size) { index ->
        measured[model(candidates[index]).pair]?.let { it.b / it.a } ?: 0.5
    }
    val indices: Series<Int> = candidates.size j { it }
    val order = indices.sortedWith(Comparator { left, right -> scores[right].compareTo(scores[left]) })
    return order α { candidates[it] }
}
