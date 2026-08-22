
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

## 2025-05-24 - SQL Injection Prevention
**Vulnerability:** In `HermesDonorTrace.kt`, queries to the `tasks` table were constructed manually via `createStatement().executeQuery(...)`. Although currently executing a hardcoded `SELECT id, title, body, status, parent_ids FROM tasks ORDER BY id ASC` string, constructing statements statically leaves room for future SQL injections if filters/where clauses are appended dynamically without migration to safe query methods.
**Learning:** Hardcoded query strings in `executeQuery` expose applications to a high risk of SQL injection if developer changes ever append user parameters. Using prepared statements prevents injection via parameter binding separation.
**Prevention:** Always use `prepareStatement` over `createStatement` when executing queries, even for initially parameter-less queries, to ensure the safest default posture.
