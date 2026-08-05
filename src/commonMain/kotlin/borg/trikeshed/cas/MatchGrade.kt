package borg.trikeshed.cas

enum class MatchGrade(val rampScore: Double) {
    CONFIRMED(1.0),
    PROVISIONAL(0.45),
    CANDIDATE(0.12)
}

enum class LinkConfidence(val rampScore: Double) {
    CONFIRMED(1.0),
    PROVISIONAL(0.45),
    CANDIDATE(0.12)
}
