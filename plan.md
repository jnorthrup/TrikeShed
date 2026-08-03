1. Modify `src/commonMain/kotlin/forge/doc/WorkDrain.kt` using `replace_with_git_merge_diff`
   - Add a new function `drainSession17389565571177971407` that appends a `WorkDrained` event with dummy signature to supersede the necromanced task.
   - Register this function inside `drainWork` to ensure it is invoked.
2. Read the modified file to verify changes using `cat src/commonMain/kotlin/forge/doc/WorkDrain.kt`.
3. Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done.
4. Invoke the `request_code_review` tool.
