package borg.trikeshed.couch.isam

import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.ConfixIndex
import borg.trikeshed.parse.confix.ConfixIndexK

/**
 * Target isomorphism (NOT YET IMPLEMENTED):
 *
 *     ConfixIndex (facet: Tags / KeyToChild / DirectChildren / Spans / Depths)
 *       ↕
 *     ISAM schema (Series<RecordMeta>)
 *
 * A scanned JSON / CBOR / YAML document maps onto a flat columnar layout by
 * reading the `ConfixIndexK` facets — no AST, no second parse. Specifically:
 *
 *   - `Tags`        → per-token `IOMemento` discriminant → `RecordMeta.type`
 *   - `Spans`       → byte offsets → `RecordMeta.begin` / `end`
 *   - `DirectChildren` / `KeyToChild` → object property names → `RecordMeta.name`
 *
 * The previous body of this object emitted `col_$i` / `"string"` for every
 * row regardless of content (`"We fake extraction here for the structural
 * proof"`). That produced plausible-shaped `RecordMeta` from any cursor, so
 * `ConfixIsamFactory.kt:37` — the sole caller — shipped a garbage schema with
 * no test failures. Do not reintroduce the fake; this throws until the real
 * facet-driven extraction lands. The TDD'd Confix index surface
 * (`ConfixIndexK`, `ConfixKit`, `Syntax.scan`, `StructuralSharingTest`) does
 * the indexing; the missing cut is wiring those facets into `RecordMeta`
 * here, against the `ConfixIndex` (not `Cursor`) shape.
 */
object ConfixIsamIsomorphism {

    /**
     * Extracts a flat ISAM schema from a Confix index. Unimplemented — see
     * class kdoc. Will not silently synthesise column metadata.
     */
    fun inferIsamSchemaFromConfixIndex(index: ConfixIndex): Series<RecordMeta> =
        TODO("inferIsamSchemaFromConfixIndex: read ConfixIndexK.Tags/Spans, walk DirectChildren/KeyToChild, emit RecordMeta")
}
