package borg.trikeshed.utils.keymuxd

// Canonical implementation lives in `keymux` (commonMain) and is re-exported via
// src/commonMain/kotlin/borg/trikeshed/utils/keymuxd/KeyMux.kt (typealiases).
// A duplicate set of aliases here causes a redeclaration conflict with commonMain
// when both source sets are merged for the JVM target. Keep this file as a marker
// only — do not add declarations.
