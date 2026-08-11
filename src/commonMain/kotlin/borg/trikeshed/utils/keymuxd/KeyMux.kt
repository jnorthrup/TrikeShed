package borg.trikeshed.utils.keymuxd

// Canonical implementation lives in `keymux` (commonMain).
// This file was a 93.4%-similar copy that had already drifted (persist codec, cache
// semantics, watch() behavior). Aliased away — see keymux/KeyMux.kt.

typealias KeyPath = keymux.KeyPath
typealias KeyResult = keymux.KeyResult
typealias KeyBinding = keymux.KeyBinding
typealias KeyMuxCore = keymux.KeyMuxCore

typealias KeySource = keymux.KeySource
typealias EnvSource = keymux.EnvSource
typealias EnvVarSource = keymux.EnvVarSource
typealias PersistSource = keymux.PersistSource
typealias ApiSource = keymux.ApiSource
typealias ReactorSource = keymux.ReactorSource
typealias FixedKeySource = keymux.FixedKeySource
typealias TestKeySource = keymux.TestKeySource

typealias KeyResolver = keymux.KeyResolver
typealias FirstWinsResolver = keymux.FirstWinsResolver

typealias KeyMux = keymux.KeyMux
typealias KeyMuxBuilder = keymux.KeyMuxBuilder
