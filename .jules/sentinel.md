## 2024-05-14 - Unawaited Process Termination
**Vulnerability:** Processes were forcefully killed using `destroyForcibly()` without waiting for the OS to complete termination, leading to potential resource leaks and deadlock states.
**Learning:** `Process.destroyForcibly()` sends a kill signal but operates asynchronously. If the process handle is not awaited, resources remain allocated and querying status leads to errors.
**Prevention:** Always invoke `waitFor()` immediately after `destroyForcibly()` to safely block until the OS confirms termination.
