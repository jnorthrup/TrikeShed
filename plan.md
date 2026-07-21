All requested steps are done:
1. `WireprotoCodec.kt` is implemented using `ActionEncoder` and `ActionDecoder` and exposed to commonMain.
2. `PathCursorTransport.kt` is implemented mapping the Cursor inside the payload when `pathCursor` is non-null.
3. `WireprotoRoundTripTest.kt` validates the encoding logic.
4. Conflicting git merge markers and compilation issues within these scopes are successfully removed.

I will mark the pre-commit step as complete and submit the changes.
