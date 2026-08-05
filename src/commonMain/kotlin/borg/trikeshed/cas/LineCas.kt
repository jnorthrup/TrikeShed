package borg.trikeshed.cas

enum class MatchGrade {
    CONTENT_ONLY,
    PARTIAL_LEFT,
    PARTIAL_RIGHT,
    LINKED
}

enum class LinkConfidence {
    CANDIDATE,
    PROVISIONAL,
    CONFIRMED
}

fun confidenceOf(grade: MatchGrade): LinkConfidence = when (grade) {
    MatchGrade.CONTENT_ONLY -> LinkConfidence.CANDIDATE
    MatchGrade.PARTIAL_LEFT, MatchGrade.PARTIAL_RIGHT -> LinkConfidence.PROVISIONAL
    MatchGrade.LINKED -> LinkConfidence.CONFIRMED
}

/**
 * Returns a log-ish spaced confidence score based on match grade.
 * Note: This score is a prior confidence, not a probability proof.
 */
fun rampScore(grade: MatchGrade): Double = when (grade) {
    MatchGrade.CONTENT_ONLY -> 0.12
    MatchGrade.PARTIAL_LEFT, MatchGrade.PARTIAL_RIGHT -> 0.45
    MatchGrade.LINKED -> 1.0
}
