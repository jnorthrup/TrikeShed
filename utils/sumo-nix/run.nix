# nix-instantiate --eval --strict --json run.nix --arg sumoNix /pinned/sumo-nix --arg input /export/input.json
# Executes the real pinned upstream solver. No ontology/corpus fetch, effects or NAF policy is added here.
{ sumoNix, input }:
assert builtins.hashFile "sha256" (sumoNix + "/datalog.nix") == "9953ebeb4c4792817f49ff00ff16a4fb82945ff44b2778eecb7f3651e4e7fdd7";
let
  data = builtins.fromJSON (builtins.readFile input);
  expected = "1e2fb138d562769d21e7f3e6cfdade9129705ec4";
  variable = s: builtins.isString s && builtins.substring 0 1 s == "?";
  relation = f: builtins.isList f && builtins.length f >= 1 && builtins.length f <= 17
    && builtins.all builtins.isString f && !(variable (builtins.head f))
    && !(builtins.elem (builtins.head f) [ "and" "or" "not" "exists" "forall" "equal" "=>" "<=>" "iff" "implies" "equivalence"
      "modalAttribute" "believes" "knows" "desires" "holdsDuring" "holds" "necessarily" "possibly" ])
    && builtins.all (s: builtins.substring 0 1 s != "@" && builtins.stringLength s <= 65536) f;
  ground = f: relation f && builtins.all (s: !(variable s)) f;
  body = p: if builtins.isList p && p != [] && builtins.head p == "and" then builtins.tail p else [ p ];
  safeRule = r: builtins.isList r && builtins.length r == 3 && builtins.head r == "=>"
    && (let ps = body (builtins.elemAt r 1); c = builtins.elemAt r 2;
      bound = builtins.concatLists ps; in
      builtins.length ps >= 1 && builtins.length ps <= 16 && builtins.all relation ps && relation c
      && builtins.all (s: !(variable s) || builtins.elem s bound) c);
  e = import (sumoNix + "/datalog.nix") { ontology = null; inherit (data) facts rules; };
  show = x: if builtins.isString x then x else "(" + builtins.concatStringsSep " " (map show x) + ")";
  proof = e.why data.query;
  sourceProof = p:
    if p ? asserted then {
      inherit (p) fact;
      asserted = true;
      sources = map (entry: entry.source) (builtins.filter (entry: show entry.fact == p.fact) data.provenance.facts);
    } else {
      inherit (p) fact rule;
      sources = map (entry: entry.source) (builtins.filter (entry: entry.id == p.rule) data.provenance.rules);
      from = map sourceProof p.from;
    };
in
assert data.schema == "sumo-nix-positive-horn/1" && data.sumoNixRevision == expected && data.ontology == null
  && data.mlirNixRevision == "1547e4a37ea1393a7cd3e3cfe7a89aff38a36dba"
  && builtins.elem data.scope [ "host-explicit-assertions" "review-record-observations" ]
  && builtins.length data.facts + builtins.length data.rules <= 128
  && builtins.all ground data.facts && builtins.all safeRule data.rules && ground data.query;
{
  kind = "sumo-nix-positive-horn-result/1";
  inherit (data) scope frameId revision query excluded;
  inputCid = "sha256:" + builtins.hashFile "sha256" input;
  sumoNixRevision = expected;
  status = if proof == null then "UNKNOWN" else "SUPPORTED";
  semantics = "positive-scalar-horn-first-proof/1";
  firstProofOnly = true;
  ontology = null;
  execution = "pinned-upstream-sumo-nix-datalog";
  # UNKNOWN is absence in this declared positive fragment, never refutation.
  proof = if proof == null then null else sourceProof proof;
  # External process timeout/failure is no result, not UNKNOWN. The host must cap
  # runtime, memory and output; this entry point promises no finite step budget.
  solverStepBudget = null;
}
