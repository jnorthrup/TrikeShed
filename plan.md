1. **Modify `FlywheelDriver.cycle()`**:
   - Introduce a PRODUCER step before dispatching: read `doc/todo.md`, and for each uncompleted task, append a `JulesCause.WorkQueued` to the WAL using `store.appendWork()`.
   - Calculate a `score` based on the item's position in the file (earlier items get higher scores) so they can be prioritized. Generate a deterministic `workId` from the title (e.g., using a hash).
   - The DEDUP mechanism is natively handled by `store.loadQueue()` due to its `getOrPut` first-wins behavior on WAL replay.
   - Load the unified queue via `store.loadQueue()`.
   - Filter for items that are not dispatched (`!it.isDispatched`).
   - Sort the queue descending by `score` (`score desc`).
   - Take the top `available` items.
   - Dispatch them via `client.createSession()`.
   - On success, append a `JulesCause.WorkDispatched` to the WAL.

2. **Pre-commit Checks**: Ensure it passes `jvmTest` and all tests.
