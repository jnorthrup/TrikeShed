package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*
import kotlin.test.*

class ModelProficiencyTest {
    val task: ProficiencyTask = "kanban-plane-v1" j "summary"
    val a: ProficiencyModel = "a" j "v1"
    val b: ProficiencyModel = "b" j "v1"

    fun grade(
        attempt: String,
        model: ProficiencyModel,
        sequence: Long,
        score: Double?,
        task: ProficiencyTask = this.task,
        basis: String = ModelGradeBasis.EVIDENCE,
        correctness: ModelCorrectness = ModelCorrectness.UNKNOWN,
        disposition: ModelDisposition = ModelDisposition.UNSELECTED,
    ): ModelGrade = ModelGrade(attempt, model, task, sequence, basis, score, correctness, disposition)

    fun rank(
        candidates: Series<ProficiencyModel>,
        grades: Series<ModelGrade>,
        basis: String = ModelGradeBasis.EVIDENCE,
    ): Series<ProficiencyModel> = rankModelProficiency(candidates, grades, task, basis) { it }

    fun assertOrder(expected: Series<ProficiencyModel>, actual: Series<ProficiencyModel>) {
        assertEquals(expected.size, actual.size)
        for (index in 0 until expected.size) assertEquals(expected[index].pair, actual[index].pair, "rank $index")
    }

    @Test
    fun taskHarnessAndModelRevisionAreIsolated() {
        val revision: ProficiencyModel = "a" j "v2"
        val unknown: ProficiencyModel = "a" j null
        val grades = s_[
            grade("current", a, 1, 1.0),
            grade("revision", revision, 2, 0.0),
            grade("unknown", unknown, 3, 0.0),
            grade("other-task", a, 4, 0.0, task.a j "review"),
            grade("other-harness", a, 5, 0.0, "kanban-plane-v2" j task.b),
        ]

        assertOrder(s_[a, b, revision, unknown], rank(s_[revision, b, unknown, a], grades))
    }

    @Test
    fun correctionAndReplayContributeOncePerAttempt() {
        val corrected = grade("first", a, 4, 0.0)
        val separate = grade("second", a, 2, 1.0)
        val grades = s_[
            corrected,
            grade("first", a, 1, 1.0),
            separate,
            separate,
            separate,
            grade("comparison", b, 3, 0.6),
            corrected,
        ]

        // Two distinct attempts average to 0.5, regardless of replay multiplicity or input order.
        assertOrder(s_[b, a], rank(s_[a, b], grades))
    }

    @Test
    fun humanAssessmentDoesNotEraseItsEvidenceGrade() {
        val grades = s_[
            grade("a-attempt", a, 1, 0.9),
            grade("b-attempt", b, 2, 0.8),
            grade("a-attempt", a, 3, 1.0, basis = ModelGradeBasis.CORRECTNESS,
                correctness = ModelCorrectness.INACCURATE),
        ]
        val evidence = rank(s_[b, a], grades)

        assertOrder(s_[a, b], evidence)
        assertOrder(s_[b, a], rank(evidence, grades, ModelGradeBasis.CORRECTNESS))
    }

    @Test
    fun rejectedExperimentCanBeAccurateAndSelectionCanBeInaccurate() {
        val rejected: ProficiencyModel = "rejected" j "v1"
        val grades = s_[
            grade("selected-accurate", a, 1, 0.0, basis = ModelGradeBasis.CORRECTNESS,
                correctness = ModelCorrectness.ACCURATE, disposition = ModelDisposition.SELECTED),
            grade("selected-inaccurate", b, 2, 1.0, basis = ModelGradeBasis.CORRECTNESS,
                correctness = ModelCorrectness.INACCURATE, disposition = ModelDisposition.SELECTED),
            grade("rejected-accurate", rejected, 3, 0.0, basis = ModelGradeBasis.CORRECTNESS,
                correctness = ModelCorrectness.ACCURATE, disposition = ModelDisposition.EXPERIMENT_REJECTED),
        ]

        assertOrder(s_[a, rejected, b], rank(s_[a, b, rejected], grades, ModelGradeBasis.CORRECTNESS))
    }

    @Test
    fun transportSuccessAndDoneDoNotSupplyAGrade() {
        val fields = mapOf(
            "attemptId" to "transport",
            "model" to a.a,
            "modelVersion" to a.b,
            "harness" to task.a,
            "taskKind" to task.b,
            "ok" to true,
            "decision" to "DONE",
            "basis" to ModelGradeBasis.EVIDENCE,
            "correctness" to "UNKNOWN",
            "disposition" to "SELECTED",
        )
        val transport = assertNotNull(modelGrade(fields, 1))

        assertNull(transport.score)
        assertEquals(ModelCorrectness.UNKNOWN, transport.correctness)
        assertOrder(s_[b, a], rank(s_[b, a], s_[transport]))
        assertNull(modelGrade(fields - "basis", 1))
    }

    @Test
    fun measuredFailureRanksBelowAnUnmeasuredCandidate() {
        assertOrder(s_[b, a], rank(s_[a, b], s_[grade("failed-evidence", a, 1, 0.0)]))
    }

    @Test
    fun latestUnknownWithdrawsHumanAccuracyEvenWithAScore() {
        val grades = s_[
            grade("attempt", a, 2, 1.0, basis = ModelGradeBasis.CORRECTNESS),
            grade("attempt", a, 1, 1.0, basis = ModelGradeBasis.CORRECTNESS,
                correctness = ModelCorrectness.ACCURATE),
        ]

        assertOrder(s_[b, a], rank(s_[b, a], grades, ModelGradeBasis.CORRECTNESS))
    }
}
