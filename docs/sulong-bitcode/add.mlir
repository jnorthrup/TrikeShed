// Standard MLIR arithmetic: this does not exercise a Nix dialect or evaluator.
module attributes {llvm.target_triple = "x86_64-unknown-linux-gnu"} {
  func.func @add(%left: i32, %right: i32) -> i32 {
    %sum = arith.addi %left, %right : i32
    return %sum : i32
  }
}
