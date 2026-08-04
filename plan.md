1. **Analyze existing code in `JobReducer.kt`**
   - The file uses `CanonicalCbor.encode(frame.doc)` when processing `JobFrame`.
   - The current code already implements deterministic CIDs for `JobFrame` using `CanonicalCbor`.
   - The user asked to re-read the code and if already covered by a landed session, supersede with a receipt-bearing `WorkDrained`.

2. **Verify test passing**
   - Run tests to see if tests pass.

3. **Supersede necromanced work**
   - Append to `WorkDrain.kt` a `WorkDrained` entry for `session:4465036716017209747`.
   - The method to use is `appendWork`.
   - Create a drain function named `drainSession4465036716017209747`.

4. **Verify changes**
   - Use `tail` on `WorkDrain.kt` to ensure changes are applied.

5. **Run Pre-Commit Checks**
   - Invoke `pre_commit_instructions` and follow the provided steps.

6. **Submit Code Review**
   - Request a code review.
