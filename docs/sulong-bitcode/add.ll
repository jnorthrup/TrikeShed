; Handwritten LLVM IR, assembled with installed llvm-as 22.1.8.
; Corresponds to add.c for the tested, non-overflowing integer arguments.
source_filename = "handwritten-add.ll"
target triple = "x86_64-unknown-linux-gnu"

define i32 @add(i32 %a, i32 %b) {
entry:
  %sum = add i32 %a, %b
  ret i32 %sum
}
