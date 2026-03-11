# Expanded Cpp2 Spec From Kotlin Text

## Position

- TrikeShed Kotlin text is the reference surface.
- cpp2 needs to expand until it can honestly carry this shape.
- The port starts as local spec text, not as a claim that `../cppfort` already implements it.

## Design Axioms

- semantic objects first
- dense lowered views second
- early normalization to a small canonical AST
- Kotlin proven forms are carried forward instead of being flattened into template noise

## Kotlin-to-cpp2 Text Mapping

| Kotlin text | TrikeShed meaning | Expanded cpp2 text target | Canonical node |
| --- | --- | --- | --- |
| `a j b` | join/product pair | `a j b` | `join` |
| `n j { i: Int -> expr }` | indexed semantic object | `n j (i: int) => expr` | `indexed` |
| `series α { x -> expr }` | semantic projection | `series α (x) => expr` | `map` |
| `coordinatesOf(1.0, 2.0)` | semantic coordinates | `coords[1.0, 2.0]` | `coordinates` |
| `chart.locate(point)` | point to local coordinates | `chart.project(point)` | `chart_project` |
| `chart.point(coords)` | local coordinates to point | `chart.embed(coords)` | `chart_embed` |
| `atlas.locate(point)` | first matching chart plus coordinates | `atlas.locate(point)` | `atlas_locate` |
| `manifold.transition(from, to, coords)` | chart reprojection through the semantic point | `manifold.transition(from, to, coords)` | `transition` |
| `coords.lowered()` | explicit dense/materialized view | `coords.lowered()` | `lower_dense` |

## Minimal Grammar Delta

```ebnf
coordinate_literal  ::= "coords" "[" expression_list "]"
chart_decl          ::= "chart" identifier "(" parameter_list ")" block
atlas_literal       ::= "atlas" "[" expression_list "]"
manifold_decl       ::= "manifold" identifier "=" expression
transition_expr     ::= expression "." "transition" "(" expression "," expression "," expression ")"
```

## Normalization Rules

1. `coords[...]` normalizes to a semantic `coordinates` node backed by indexed access.
2. `chart.project(point)` normalizes to a chart-projection node.
3. `chart.embed(coords)` normalizes to a chart-embedding node.
4. `atlas.locate(point)` normalizes to ordered chart selection plus `join(chart, coordinates)`.
5. `coords.lowered()` normalizes to an explicit dense-view node and never aliases the semantic coordinates type.

## Manifold Example

```text
let shifted = chart shifted(point: f64) {
  contains point > 5.0
  project -> coords[point - 10.0]
  embed(local) -> local[0] + 10.0
}

let identity = chart identity(point: f64) {
  contains point <= 20.0
  project -> coords[point]
  embed(local) -> local[0]
}

let line = manifold line = atlas[shifted, identity]
let local = line.transition("identity", "shifted", coords[17.0])
let dense = local.lowered()
```

## Non-Goals

- No promise that current cpp2 tooling already accepts this syntax.
- No collapse of semantic coordinates into dense storage.
- No requirement that the spec be expressed as templates first.

---

## Full Grammar Delta: Original Cpp2 Additions

Beyond manifold/chart, the transcript proposes these **language-level additions** that cppfront does not have. This is what makes cppfort original:

```ebnf
(* --- TrikeShed core lib port (minimal) --- *)
join            ::= expr 'j' expr
indexed         ::= domain 'j' accessor
series          ::= indexed<int>
alpha           ::= series 'α' transform
fold            ::= series '.fold(' init ',' accumulator ')'
slice           ::= series '[' from '..' to ']'
cursor          ::= series<series>

(* --- Original language additions --- *)
series_literal  ::= '_s' '[' expr { ',' expr } ']'
elementwise_mul ::= expr '**' expr
elementwise_add ::= expr '++' expr
indexed_view    ::= expr '*[' expr ']'
grad_diff       ::= 'grad' '(' expr ',' var ')'
dense_view      ::= 'dense' '(' series_expr ')'

(* --- Semantic annotations (language-level, not library) --- *)
rank_annotation   ::= '<' 'rank' '=' integer '>'
axis_annotation   ::= '<' 'axis' '=' id { ',' id } '>'
purity_contract   ::= '[[' ('pure' | 'contiguous' | 'non_aliasing' | 'strided') ']]'
```

### What These Add Beyond Cppfront

| Production | Cppfront status | What it gives cpp2 |
| --- | --- | --- |
| `_s[1,2,3]` | Does not exist | Series literal — becomes `indexed<int, F>` with constant domain |
| `a ** b` | Not an operator | Elementwise multiply — SoN sees pointwise structure |
| `s*[i]` | Not a pattern | Indexed view projection — SoN knows the access pattern |
| `grad(e, v)` | No AD concept | First-class differentiation — `grad_diff` as SoN node |
| `dense(s)` | No semantic/dense split | Explicit lowering boundary — SoN knows when aliasing rules change |
| `[[pure]]` etc | Contracts are new in C++26 | View contracts on expressions — SoN can prove aliasing/contiguity |
| `rank=N` | Template gymnastics | First-class rank — no tuple games for tensor dimensions |

## Ergonomic Gaps: Where Cpp2 Needs More Room

Extracted from transcript message 11. These are where cpp2 has runtime power but lacks type-level expressiveness:

| Gap | C++26 / cpp2 status | What the design needs |
| --- | --- | --- |
| **Callable carriers** (`indexed<I,F>` plumbing) | Concepts + deducing `this` + reflection (P2996) | True "function-from-domain" syntax. `operator[]` sugar hints at it. |
| **Associated types / rich protocols** | Concepts improved but still weak (no HKTs) | Reflection + contracts give 80%. Missing 20% is why early normalization to canonical nodes matters. |
| **Const-generic rank/axis** | Pack indexing + `std::integral_constant` + reflection | First-class `consteval` rank/axis literals in the language. |
| **Multidimensional view vocabulary** | `std::mdspan` (C++23) + reflection extensions | `dense_tensor` should be `mdspan` + semantic axes metadata. |
| **Purity/aliasing/contiguity contracts** | Contracts (C++26!) + `[[restrict]]` proposals | Language-level "contiguous, non-aliasing, strided" that SoN can see through canonical AST. |
| **Symbolic ↔ numeric staging** | Reflection + `consteval` + `is_constant_evaluated` | The design solves it structurally. Language just needs to stop forcing same type for both. |

Verdict: cpp2 has the runtime power. The missing room is type-level and view/protocol ergonomics. Early normalization (textual sugar → canonical nodes → SoN) is the bridge that works today while the language catches up.

## SoN Canonical Nodes (What the Grammar Reduces To)

After normalization, all surface grammar collapses to these IR ops:

| Canonical node | From surface grammar | SoN behavior |
| --- | --- | --- |
| `indexed_op` | `j`, `_s[]`, `series` | domain × accessor → value |
| `atlas_op` | `atlas[]`, `chart`, `manifold` | chart_index × coord → value |
| `grad_diff_op` | `grad(e, v)` | expr × var → expr (chain rule rewrite) |
| `transition_op` | `.transition(from, to, coords)` | reprojection through semantic point |
| `dense_tensor_op` | `dense(s)`, `.lowered()` | axes × strides × data → value (contiguous) |
| `coordinates_op` | `coords[...]` | semantic position in chart domain |
