
## 2024-05-15 - Command Injection in Git Tag Parsing
**Vulnerability:** Output from git tag -l was fed directly into a new ProcessBuilder command without validation.
**Learning:** Even internal tool outputs like git tags can be manipulated in a shared repository and lead to command or argument injection when used dynamically in subsequent shell commands.
**Prevention:** Always validate and sanitize dynamic inputs, even if they originate from local repository metadata, before passing them to ProcessBuilder.

## 2024-05-15 - Hardcoded Keys
**Learning:** Found mock "sk-test" API keys and similar dummy strings in test files (`src/commonTest/kotlin/keymux/KeyMuxTest.kt` and `src/commonTest/kotlin/modelmux/ModelMuxTest.kt`). While not a vulnerability, it is important to ensure these mock keys are not copied into production code.
**Action:** No action required for test files. Ensured production `BrainClient.kt` dynamically loads API keys from environment variables or secure sources like `KeyMux` and `SystemOperations.default.getenv`.

## 2024-05-15 - Hardcoded Secrets Mitigation
**Learning:** Found mock API keys in test files. Although these aren't live secrets, it highlights the importance of providing a structured way for developers to inject secrets locally without committing them.
**Action:** Added `.env.example` with placeholder keys to document the required environment variables (e.g., `JULES_API_KEY`, `OPENAI_API_KEY`) and provide a secure template for local configuration, reinforcing the practice of keeping secrets out of version control.

## 2024-05-15 - DoS Vulnerability in Random Number Generation
**Vulnerability:** The codebase was using `java.security.SecureRandom.getInstanceStrong()` to generate random bytes for temporary passwords and peer IDs.
**Learning:** On Linux/Unix systems, `getInstanceStrong()` often defaults to the blocking `/dev/random` pool. If system entropy is depleted, any thread calling `nextBytes()` on this instance will block indefinitely, leading to a Denial of Service (DoS) and application hang.
**Prevention:** Use the default `SecureRandom()` constructor instead. It utilizes the non-blocking CSPRNG (`/dev/urandom`), which is cryptographically strong enough for general application use and immune to entropy-depletion blocking.

<<<<<<< ours
<<<<<<< HEAD
## 2023-11-20 - [Denial of Service via Unbounded waitFor]
**Vulnerability:** Found `waitFor()` being called on a `Process` without a timeout in `JvmProcessOperations.kt`.
**Learning:** `Process.waitFor()` without a timeout can lead to thread starvation and Denial of Service (DoS) if the subprocess hangs. This violates the "Fail securely" and "Do not expose system resources" principles.
**Prevention:** Always use bounded `waitFor(timeout, TimeUnit)` and explicitly terminate the process via `destroyForcibly()` if the timeout occurs.

=======
>>>>>>> theirs
## 2025-05-24 - SQL Injection Prevention
**Vulnerability:** In `HermesDonorTrace.kt`, queries to the `tasks` table were constructed manually via `createStatement().executeQuery(...)`. Although currently executing a hardcoded `SELECT id, title, body, status, parent_ids FROM tasks ORDER BY id ASC` string, constructing statements statically leaves room for future SQL injections if filters/where clauses are appended dynamically without migration to safe query methods.
**Learning:** Hardcoded query strings in `executeQuery` expose applications to a high risk of SQL injection if developer changes ever append user parameters. Using prepared statements prevents injection via parameter binding separation.
**Prevention:** Always use `prepareStatement` over `createStatement` when executing queries, even for initially parameter-less queries, to ensure the safest default posture.
<<<<<<< ours

## 2024-05-24 - [Unchecked Casts on JsonSupport.parse lead to ClassCastException/DoS]
**Vulnerability:** Core JSON deserialization logic (`JsonSupport.parse`) returns unvalidated `Any?`, which callers universally cast directly using unchecked casts (`as Map<String, Any?>` or similar). This creates an immediate exception and potential DoS vulnerability if the payload structure changes or is manipulated (e.g., in WAL replay).
**Learning:** Kotlin Multiplatform `Any?` deserialization without strict schema wrappers or inline type bounds checking creates brittle boundaries that violate fail-secure principles. A simple structural mismatch crashes the execution thread.
**Prevention:** Introduce and enforce explicitly typed validator functions (e.g., `parseMap(text: String): Map<String, Any?>`) at the library boundary (`JsonSupport`) that safely validate structure and type using `require` blocks before applying casts, throwing standardized descriptive exceptions that callers can catch cleanly.
>>>>>>> origin/sentinel/fix-json-unsafe-deserialization-13206169233468597183
=======
>>>>>>> theirs

## 2024-06-25 - [Predictable PRNG in WebSocket Handshake]
**Vulnerability:** The `generateKey()` method in `Rfc6455Handshake.kt` used a highly predictable linear shift-XOR algorithm seeded by the system clock (`Clock.System.now().toEpochMilliseconds()`) to generate the `Sec-WebSocket-Key`.
**Learning:** Using predictable, time-based PRNGs for cryptographic nonces like `Sec-WebSocket-Key` makes the handshake susceptible to prediction or replay attacks. While the RFC 6455 states this key is not meant for authentication, it is meant to prove the request is actually a WebSocket request and to prevent caching proxy issues, so it should still be robustly random.
**Prevention:** Always use standard, secure-by-default libraries for random number generation (e.g., `kotlin.random.Random.Default.nextBytes` or `SecureRandom`) instead of rolling custom cryptographic algorithms or using simple PRNGs.

<<<<<<< HEAD
## 2024-05-24 - [Denial of Service via Pipe Buffer Deadlock]
**Vulnerability:** Calling `Process.waitFor()` before fully reading the child process's standard output/error (or reading synchronously before `waitFor()` without a timeout).
**Learning:** If a child process writes more data to standard output/error than the OS pipe buffer can hold, it will block until the buffer is drained. If the parent thread is simultaneously blocked on `waitFor()` waiting for the child to exit (or blocked on a synchronous stream read while the child hangs), it creates a deadlock or thread starvation, leading to a Denial of Service.
**Prevention:** Always read process streams asynchronously (e.g., via Kotlin coroutines `async` or Java `CompletableFuture`) while the main thread safely awaits the process completion using a bounded `waitFor(timeout)` call. If a timeout occurs, terminate the process aggressively via `destroyForcibly()`.
=======
## 2024-05-24 - ProcessBuilder Environment Leak Mitigation
**Vulnerability:** `JvmProcessPipe` created a `ProcessBuilder` which inherits the host process environment variables by default, potentially leaking secrets to untrusted guest code.
**Learning:** `ProcessBuilder` copies the parent environment. When spawning processes for untrusted code execution, the environment must be explicitly cleared and populated with only a curated whitelist of safe variables.
**Prevention:** Always clear `ProcessBuilder.environment()` and populate it explicitly from a whitelist (like `GuestEnvironment.curated()`) when launching untrusted guests.
>>>>>>> origin/sentinel-fix-processbuilder-env-leak-4933897859296758517
