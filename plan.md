1. **Understand requirements & Explore**: I will create `MonoChannelTest` to verify that `MonoChannel` can read from stdin and write to stdout using 8-byte message framing with non-blocking reads. I will use `kotlinx.io`'s mock buffers for stdin/stdout.
2. **Write Failing Test (TDD)**: Write `MonoChannelTest.kt` inside `src/commonTest/kotlin/borg/trikeshed/channels/`, asserting the 8-byte framing behavior. Run tests to see it fail.
3. **Commit Failing Test**: I will commit this failing test to git as required by TDD.
4. **Implement Code**: Implement `MonoChannel` and `ChannelDefinition.Enum` in `src/commonMain/kotlin/borg/trikeshed/channels/`. It will read from stdin (a `Source`), check for 8-byte frames (e.g., length prefix), and write to stdout (a `Sink`).
5. **Verify Tests**: Run tests again to ensure green state.
6. **Pre-commit step**: Execute pre commit instructions.
7. **Commit Implementation**: Commit the passing implementation.
