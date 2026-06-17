"""tspy.kanban.idioms — TrikeShed idiom catalog for LoRA target knowledge.

These are the kernel-algebra patterns from PRELOAD.md that the adapted model
(Gemma-4B or similar) must internalize. The kanban LoRA training/eval loop
scores against these.
"""

from __future__ import annotations

# Each idiom is (pattern_name, kotlin_form, python_form, eval_prompt)
IDIOMS = [
    ("join_compose",
     "A j B  ->  Join<A,B>",
     "j(a, b)  ->  (a, b)",
     "Given two values, compose them into a Join pair using j"),

    ("twin_same_type",
     "Twin<T> = Join<T,T>",
     "twin(x, y)  ->  (x, y)",
     "Create a same-typed pair (Twin) from two identical-type values"),

    ("series_index",
     "Series<T> = Join<Int, (Int)->T>",
     "Series(size, index_fn)",
     "Define an indexed series of size N with a lazy index oracle"),

    ("alpha_project",
     "series.a(xform)  ->  Series<C>",
     "series.alpha(fn)  ->  new Series",
     "Apply a lazy projection over a series without materializing"),

    ("range_view",
     "cursor[i0 until i1]",
     "series.range(start, end)",
     "Create a range view into a series from i0 to i1"),

    ("column_select",
     'cursor["name","age"]',
     "select(cursor, 'name', 'age')",
     "Project cursor columns by name"),

    ("column_exclude",
     'cursor[-"debug"]',
     "exclude(cursor, 'debug')",
     "Exclude a column from a cursor by name"),

    ("cursor_join",
     "join(c1, c2)  ->  wider cursor",
     "cursor_join(c1, c2)",
     "Widen two cursors along columns"),

    ("cursor_combine",
     "combine(c1, c2)  ->  taller cursor",
     "combine(c1, c2)",
     "Concatenate two cursors along rows"),

    ("left_identity",
     "t.loop  ->  () -> T",
     "constant(t)  ->  () -> t",
     "Create a left-identity thunk from a value"),

    ("literal_macros",
     "_l[1,2,3]  _a[...]  _s[...]  s_[...]",
     "_l(1,2,3)  _a(...)  _s(...)  s_(...)",
     "Use collection literal macros for dense composition"),

    ("cseries_comparable",
     "Series<T>.cpb  ->  CSeries<T>",
     "CSeries(series)",
     "Promote a series to a comparable CSeries"),

    ("ring_buffer",
     "RingSeries<T>(capacity=2048)",
     "RingSeries(capacity=2048)",
     "Create a power-of-2 ring buffer for hot-path events"),

    ("field_synapse",
     "FieldSynapse(phase, opcode, method, addr, seq, nano, ...)",
     "FieldSynapse(phase, opcode, ...)",
     "Emit a 24-byte wire-protocol pointcut frame"),

    ("design_bias_compose",
     "composition over inheritance",
     "# prefer composition",
     "State the first design bias of the kernel algebra"),

    ("design_bias_lazy",
     "lazy views first; materialization later",
     "# lazy projections before materialization",
     "State the design bias about lazy views"),

    ("design_bias_pure",
     "keep cursor transforms pure where possible",
     "# pure transforms",
     "State the cursor rule about purity"),
]

EVAL_PROMPTS = [idiom[3] for idiom in IDIOMS]
