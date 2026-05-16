# TrikeShed RLM Plugin

Reactor Lifecycle Management (RLM) scanner and GEPA auto-fixer for TrikeShed KMP libraries.

## Scripts

- **trikeshed_rlm_scan.py**: Main scanner - checks all 38 libs for reactor choreography violations
- **trikeshed_rlm_gradle.py**: Gradle-based rule checker (any_leak, context_injection_bypass, etc.)
- **locked_llm_fixer_loop.py**: Automated GEPA loop - scan, fix, verify, repeat

## Usage

```bash
# Scan all libs
python3 .hermes/plugins/trikeshed-rlm/trikeshed_rlm_scan.py scan --libs-dir libs

# Generate report
python3 .hermes/plugins/trikeshed-rlm/trikeshed_rlm_scan.py report

# Run auto-fixer loop (locked GEPA)
python3 .hermes/plugins/trikeshed-rlm/locked_llm_fixer_loop.py
```

## Violations Detected

1. **context_injection_bypass**: Constructor params storing reactor services as fields
2. **reactor_field_hold**: Class-level fields holding reactor services
3. **missing_context_key**: Service implementations without companion Key
4. **any_leak**: `Any?` types outside whitelisted patterns
5. **mutable_stdlib_leak**: MutableList/Map/Set in commonMain
6. **library_entrypoint**: `fun main()` in library modules

## Fix Pattern (Miniduck)

```kotlin
// BEFORE (violation)
class MyService(
    private val channels: ChannelOperations,
) {
    suspend fun doWork() {
        channels.open()  // Constructor field capture
    }
}

// AFTER (fixed)
class MyService {
    suspend fun doWork() {
        val channels = currentCoroutineContext()[ChannelOperations.Key]!!
        channels.open()  // Context retrieval
    }
}
```

## Cron Job

Auto-fix loop runs hourly via hermes cron:
```
hermes cron list
```

Job: `trikeshed-rlm-gepa-loop`
- Scans all libs
- Applies miniduck pattern fixes
- Re-scans to verify
- Repeats until clean
