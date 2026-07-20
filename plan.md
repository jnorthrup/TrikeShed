1. **Modify `NuidFanoutElement.kt`**
   - Replace the `pollForWinner(...)` loop inside `NuidFanoutElement` with a `MutableSharedFlow<Claim>` mechanism to avoid scalar polling and spin-loops.
   - We will replace `accepted: Channel<Claim>` in `WorkgroupSlot` with `acceptedFlow: MutableSharedFlow<Claim>`.
   - Update `pollForWinner` to merge the `acceptedFlow`s of all candidates and use `first()` with `withTimeoutOrNull` to find the exact winner.

2. **Add Unit Test**
   - Add a unit test in `src/commonTest/kotlin/borg/trikeshed/context/nuid/NuidFanoutElementTest.kt` for 16+ candidates expecting sub-100ms selection under fanout.

3. **Verify Pre-commit**
   - Run tests to ensure changes are correct and regressions are not introduced. Note: If the Gradle build is broken due to unrelated compilation errors, the prompt states it is acceptable to remove uncompilable test files. However, if the error is in source files (`src/commonMain`), we cannot run tests fully but we can still compile and run the specific test we added (if we remove all other uncompilable files).
   - I will do my best to verify the code visually and conceptually, and then submit.

4. **Complete Pre-commit steps**
   - Complete pre commit steps to ensure proper testing, verification, review, and reflection are done.

5. **Submit the change**
   - Once all steps are completed, I will submit the change with a descriptive commit message.
