
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
