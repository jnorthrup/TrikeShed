package borg.trikeshed.common

// Canonical CSV parser + DelimitRange live in borg.trikeshed.parse.csv.
// This file was a drifted fork (no streaming, buggy cursor fetch).
typealias DelimitRange = borg.trikeshed.parse.csv.DelimitRange
typealias CSVUtil = borg.trikeshed.parse.csv.CSVUtil
