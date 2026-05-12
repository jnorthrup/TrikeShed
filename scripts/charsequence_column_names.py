#!/usr/bin/env python3
"""
charsequence_column_names.py
Widens column-name lookup parameters from CharSequence to CharSequence across
miniduck and the cursor layer so CharSeries can be passed without toString().

Discretion rules applied:
  - Structural/storage keys (DocRowVec Series<CharSequence> backing, cellsToRowVec,
    RowVec.keys property, WAL/collection/filesystem Strings) are NOT touched.
  - Only lookup-time parameters (getValue, column predicates, CursorOps funs) widen.
  - when(name) blocks in families become when(name.toString()) — Kotlin won't
    match CharSequence against CharSequence literals in a when expression.
  - CharSequence comparisons in getValue become contentEquals().
  - Aggregation targetColumn/outputColumn stay CharSequence; factory fun params widen
    and call .toString() at assignment (interpolation already handles this).

Dry-run by default. Pass --apply to write.
"""

import re
import sys
from pathlib import Path

DRY_RUN = "--apply" not in sys.argv
ROOT = Path(__file__).parent.parent

changes: list[tuple[Path, str, str, str]] = []  # (path, label, old, new)


def reg(path: Path, label: str, old: str, new: str):
    changes.append((path, label, old, new))


# ─── RowVecSupport.kt ────────────────────────────────────────────────────────
rvs = ROOT / "src/commonMain/kotlin/borg/trikeshed/cursor/RowVecSupport.kt"

reg(rvs, "getValue key param",
    "fun RowVec.getValue(key: CharSequence): Any? {",
    "fun RowVec.getValue(key: CharSequence): Any? {")

reg(rvs, "getValue meta.name comparison (RecordMeta)",
    "is RecordMeta -> if (meta.name == key) return cell.a",
    "is RecordMeta -> if (meta.name.contentEquals(key)) return cell.a")

reg(rvs, "getValue meta.a comparison (Join)",
    "is Join<*, *> -> if (meta.a == key) return cell.a",
    'is Join<*, *> -> if ((meta.a as? CharSequence)?.contentEquals(key) == true) return cell.a')

reg(rvs, "stringValue name param",
    "fun RowVec.stringValue(name: CharSequence, default: CharSequence): CharSequence =",
    "fun RowVec.stringValue(name: CharSequence, default: CharSequence): CharSequence =")

reg(rvs, "longValue name param",
    "fun RowVec.longValue(name: CharSequence): Long",
    "fun RowVec.longValue(name: CharSequence): Long")

reg(rvs, "doubleValue name param",
    "fun RowVec.doubleValue(name: CharSequence): Double",
    "fun RowVec.doubleValue(name: CharSequence): Double")

reg(rvs, "intValue name param",
    "fun RowVec.intValue(name: CharSequence): Int",
    "fun RowVec.intValue(name: CharSequence): Int")

# ─── RowVecFamilies.kt ────────────────────────────────────────────────────────
rvf = ROOT / "libs/miniduck/src/commonMain/kotlin/borg/trikeshed/miniduck/RowVecFamilies.kt"

# JsonRowVec
reg(rvf, "JsonRowVec getValue param + when",
    '    fun getValue(name: CharSequence): Any? = when (name) {\n        "nodeType" -> nodeType\n        "rawValue" -> rawValue\n        else -> null\n    }\n}',
    '    fun getValue(name: CharSequence): Any? = when (name.toString()) {\n        "nodeType" -> nodeType\n        "rawValue" -> rawValue\n        else -> null\n    }\n}')

# ViewRowVec
reg(rvf, "ViewRowVec getValue param + when",
    '    fun getValue(name: CharSequence): Any? = when (name) {\n        "id", "_id" -> id\n        "key" -> key\n        "value" -> value\n        else -> null\n    }\n}',
    '    fun getValue(name: CharSequence): Any? = when (name.toString()) {\n        "id", "_id" -> id\n        "key" -> key\n        "value" -> value\n        else -> null\n    }\n}')

# DocRowVec — getValue(name: CharSequence): complex body with cached scan
reg(rvf, "DocRowVec getValue param",
    "    fun getValue(name: CharSequence): Any? {",
    "    fun getValue(name: CharSequence): Any? {")

reg(rvf, "DocRowVec getValue cached comparison",
    "                if (cached[i] == name) return cells[i]",
    "                if (cached[i].contentEquals(name)) return cells[i]")

reg(rvf, "DocRowVec getValue linear comparison",
    "            if (keys[i] == name) return cells[i]",
    "            if (keys[i].contentEquals(name)) return cells[i]")

reg(rvf, "DocRowVec get(name) operator",
    "    operator fun get(name: CharSequence): Any? = getValue(name)",
    "    operator fun get(name: CharSequence): Any? = getValue(name)")

# ─── RowVecCompat.kt ─────────────────────────────────────────────────────────
rvc = ROOT / "libs/miniduck/src/commonMain/kotlin/borg/trikeshed/miniduck/RowVecCompat.kt"

reg(rvc, "RowVecCompat getValue param",
    "fun RowVec.getValue(name: CharSequence): Any? = rootGetValue(name)",
    "fun RowVec.getValue(name: CharSequence): Any? = rootGetValue(name)")

reg(rvc, "RowVecCompat get[] operator",
    'operator fun RowVec.get(name: CharSequence): Any? = getValue(name)',
    'operator fun RowVec.get(name: CharSequence): Any? = getValue(name)')

# ─── CursorOps.kt ─────────────────────────────────────────────────────────────
co = ROOT / "libs/miniduck/src/commonMain/kotlin/borg/trikeshed/miniduck/CursorOps.kt"

reg(co, "Eq column param",
    "fun Eq(column: CharSequence, value: Any?): (RowVec) -> Boolean",
    "fun Eq(column: CharSequence, value: Any?): (RowVec) -> Boolean")

reg(co, "Ge column param",
    "fun Ge(column: CharSequence, value: Any?): (RowVec) -> Boolean",
    "fun Ge(column: CharSequence, value: Any?): (RowVec) -> Boolean")

reg(co, "Gt column param",
    "fun Gt(column: CharSequence, value: Any?): (RowVec) -> Boolean",
    "fun Gt(column: CharSequence, value: Any?): (RowVec) -> Boolean")

reg(co, "orderBy(column: CharSequence) single-arg",
    "fun Cursor.orderBy(column: CharSequence): Cursor = orderBy(OrderSpec(column))",
    "fun Cursor.orderBy(column: CharSequence): Cursor = orderBy(OrderSpec(column.toString()))")

reg(co, "orderBy(column: CharSequence, desc) two-arg",
    "fun Cursor.orderBy(column: CharSequence, desc: Boolean): Cursor = orderBy(OrderSpec(column, desc = desc))",
    "fun Cursor.orderBy(column: CharSequence, desc: Boolean): Cursor = orderBy(OrderSpec(column.toString(), desc = desc))")

reg(co, "Cursor.minus columnName param",
    "operator fun Cursor.minus(columnName: CharSequence): Cursor {",
    "operator fun Cursor.minus(columnName: CharSequence): Cursor {")

reg(co, "Cursor.groupBy keyColumn param",
    "fun Cursor.groupBy(keyColumn: CharSequence, vararg aggregations: Aggregation): Cursor {",
    "fun Cursor.groupBy(keyColumn: CharSequence, vararg aggregations: Aggregation): Cursor {")

reg(co, "Cursor.hashJoin leftKey rightKey params",
    "fun Cursor.hashJoin(other: Cursor, leftKey: CharSequence, rightKey: CharSequence): Cursor {",
    "fun Cursor.hashJoin(other: Cursor, leftKey: CharSequence, rightKey: CharSequence): Cursor {")

reg(co, "Cursor.join leftKey rightKey params",
    "fun Cursor.join(other: Cursor, leftKey: CharSequence, rightKey: CharSequence): Cursor = hashJoin(other, leftKey, rightKey)",
    "fun Cursor.join(other: Cursor, leftKey: CharSequence, rightKey: CharSequence): Cursor = hashJoin(other, leftKey, rightKey)")

reg(co, "Aggregation.count column param",
    '    fun count(column: CharSequence = "*"): Aggregation = object : Aggregation {',
    '    fun count(column: CharSequence = "*"): Aggregation = object : Aggregation {')

reg(co, "Aggregation.sum column param",
    '    fun sum(column: CharSequence): Aggregation = object : Aggregation {',
    '    fun sum(column: CharSequence): Aggregation = object : Aggregation {')

reg(co, "Aggregation.avg column param",
    '    fun avg(column: CharSequence): Aggregation = object : Aggregation {',
    '    fun avg(column: CharSequence): Aggregation = object : Aggregation {')

reg(co, "Aggregation.min column param",
    '    fun min(column: CharSequence): Aggregation = object : Aggregation {',
    '    fun min(column: CharSequence): Aggregation = object : Aggregation {')

reg(co, "Aggregation.max column param",
    '    fun max(column: CharSequence): Aggregation = object : Aggregation {',
    '    fun max(column: CharSequence): Aggregation = object : Aggregation {')

reg(co, "private rowValue column param",
    "private fun rowValue(row: RowVec, column: CharSequence): Any?",
    "private fun rowValue(row: RowVec, column: CharSequence): Any?")

# ─── Apply or report ─────────────────────────────────────────────────────────

errors = []
applied = 0

for path, label, old, new in changes:
    try:
        text = path.read_text()
    except FileNotFoundError:
        errors.append(f"  FILE NOT FOUND: {path}")
        continue

    if old not in text:
        errors.append(f"  NOT FOUND [{label}] in {path.relative_to(ROOT)}")
        continue

    count = text.count(old)
    if count > 1:
        errors.append(f"  AMBIGUOUS ({count} hits) [{label}] in {path.relative_to(ROOT)} — skipped")
        continue

    if DRY_RUN:
        print(f"  WOULD CHANGE [{label}]  {path.relative_to(ROOT)}")
    else:
        path.write_text(text.replace(old, new, 1))
        print(f"  APPLIED [{label}]  {path.relative_to(ROOT)}")
        applied += 1

print()
if errors:
    print(f"PROBLEMS ({len(errors)}):")
    for e in errors:
        print(e)
    print()

if DRY_RUN:
    print(f"Dry run: {len(changes) - len(errors)} changes ready. Pass --apply to write.")
else:
    print(f"Applied {applied}/{len(changes)} changes.")
