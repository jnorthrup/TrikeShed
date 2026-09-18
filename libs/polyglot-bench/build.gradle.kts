// libs/polyglot-bench is a separate leaf — a CLI app used to benchmark
// TrikeShed Polyglot cold-start against Bun. All real config lives in the
// macro under case ":libs:polyglot-bench".
apply(from = "../../gradle/macros/trikeshed-lib-bench.gradle")
