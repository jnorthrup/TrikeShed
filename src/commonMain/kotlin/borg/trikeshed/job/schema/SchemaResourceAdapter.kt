package borg.trikeshed.job.schema

import borg.trikeshed.platform.CommonResources

/**
 * Schema bytes through the one common-resources door (baked bundle first, then the host's reader).
 * Formerly an `expect` with four per-target actuals that each read `src/commonMain/resources` off
 * the working directory — which silently broke from a jar, a browser, or any cwd but the repo root.
 */
fun loadConfixSchemaBytes(path: String): ByteArray =
    CommonResources.bytes(path) ?: throw IllegalStateException("Schema resource not found: $path")
