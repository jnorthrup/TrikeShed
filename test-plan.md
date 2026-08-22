1. **Create `DeterministicFormatter` interface/object:**
   - Location: `src/commonMain/kotlin/borg/trikeshed/userspace/containment/DeterministicFormatter.kt`
   - Purpose: Normalize whitespace, blank lines, and file endings in patch strings.
   - Requirements:
     - Remove trailing whitespaces
     - Collapse multiple blank lines into a single blank line
     - Ensure exactly one trailing newline at the end of the file.
     - (Maybe convert tabs to spaces, but let's check what formatting rules are implicitly needed. The prompt mentions "deterministic whitespace/ordering normalization applied to patch source before AST lint").

2. **Create `DeterministicFormatterTest` class:**
   - Location: `src/commonTest/kotlin/borg/trikeshed/userspace/containment/DeterministicFormatterTest.kt`
   - Purpose: Verify that the `DeterministicFormatter` correctly formats strings according to rules.

3. **Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done.**

4. **Verify GATE condition:**
   - `./gradlew jvmMainClasses --console=plain`
