# Daemon Launch Guide

Boot the Oroboros daemon from a clean checkout or an existing forge home. This is the prerequisite for every surface guide.

## Boot Path 0 — one command (recommended)

```bash
bin/oroboros-up
```

Does the whole sequence below and refuses to lie about it: JDK check, the
`hotswapFeed` build the daemon's classpath actually needs, a port that is really
free, a scratch forge home, readiness wait, and a printed set of URLs. Use
`--home <path>` for a real forge home, `--port N` to pin one, `--open` to launch
the board. When something misbehaves, `scripts/oroboros-doctor.sh` names the
cause and the fix.

The manual path below still works and explains what that script does.

## Boot Path A — Clean Checkout

```bash
# 1. Build the gate (once per checkout, or after source changes)
./gradlew jvmMainClasses --console=plain

# 1b. Publish those classes to what the daemon actually runs from.
#     The daemon's classpath is build/live/classes (+ build/staging/lib);
#     jvmMainClasses does NOT refresh it, and the self-heal below only fires
#     when build/live is ABSENT — a stale build/live boots silently stale, and
#     your change simply will not be there.
./gradlew hotswapFeed --console=plain

# 2. Launch into a SCRATCH home (default for newcomers)
#    ⚠ --home must be a FRESH directory. Pointing it at an existing forge
#    home operates on production state — deleting WAL files, modifying
#    CAS blobs, and changing belief bags in that home.
bin/oroboros-daemon --home /tmp/scratch-forge --repo .

# 3. The daemon serves on the default port. Verify:
curl -s http://localhost:8888/api/board
```

> **Status:** verified-live — step 1 compiles, step 2 boots, step 3 returns board JSON.

## Boot Path B — Forge-Home Hydration

When the checkout's `build/` directories are absent (e.g. on a machine with only the forge home), the daemon hydrates its classpath from CAS blobs:

```bash
# Path A wrote .oroboros/manifests/classpath.tsv into the scratch home.
# Re-boot the SAME scratch home with --boot-forge:
bin/oroboros-daemon --boot-forge /tmp/scratch-forge --home /tmp/scratch-forge
```

The classpath resolution order:
1. **Checkout build dirs** (`build/live/classes` + `build/staging/lib`) — dev mode, hotswapFeed keeps them live.
2. **Forge-home hydration** — `--boot-forge` (or `TRIKESHED_BOOT_FORGE` env) reads `.oroboros/manifests/classpath.tsv`, hardlinks blobs out of `cas/sha256/`.
3. **Gradle self-heal** — if neither is available, runs `./gradlew hotswapFeed`.

> **Status:** unverified — the self-bootstrap chain (path A → path B on the same scratch home) requires live validator confirmation. The hydration logic is present in `bin/oroboros-daemon:63-93`.

## All Flags

| Flag | Description |
|------|-------------|
| `--once` | Run one flywheel cycle, then exit |
| `--watch` | Loop forever (default) |
| `--interval-ms N` | Poll cadence in ms |
| `--home <path>` | Forge home directory |
| `--kanban-port N` | HTTP port (default 8888). Use this for a second daemon — 8888 is the operator surface, and a scratch-home daemon squatting it makes a live board look empty |
| `--repo <path>` | Repo work tree (default: cwd) |
| `--debug` | Attach JDWP on :5005, suspend=n |
| `--suspend` | JDWP suspend=y (block until debugger attaches) |
| `--hermes-root <path>` | Hermes source checkout |
| `--hermes-sleeve <path>` | GraalPy-safe overlay for /hermes |
| `--hermes-console` | Eagerly boot the VT220 panel |
| `--aot-cache <file>` | Load a JDK 25 JEP 483 cache |
| `--aot-record <file>` | Train and write a cache on exit |
| `--boot-forge <path>` | Forge home for classpath hydration |

Environment variables:
- `TRIKESHED_BOOT_FORGE` — default `--boot-forge` path.
- `TRIKESHED_AOT_CACHE` / `TRIKESHED_AOT_CACHE_OUTPUT` — AOT options (override defaults).
- `TRIKESHED_AOT_DEFAULT=0` — disables the automatic AOT training.
- `FLYWHEEL_CYCLE_INTERVAL` — overrides `--interval-ms` (supports `30m`, `2h`, `900000`).
- `TRIKESHED_EGRESS_ALLOWLIST` — comma-separated hosts the HTX client may reach.

## AOT Cache

The daemon uses JEP 514 (JDK 25) one-step AOT. On first boot it **trains** a cache; on subsequent boots with the same classpath fingerprint it **loads** the cache. The archive lives under `$XDG_STATE_HOME/trikeshed/aot/` (default `~/.local/state/trikeshed/aot/`).

- **Disable:** set `TRIKESHED_AOT_DEFAULT=0`.
- **Fingerprint:** live-classes generation + lib listing + JVM version. A hotswapFeed or toolchain bump retrains.
- **Per-instance:** two daemons off one classpath get separate archives (filename hashed from args + flags).

> **Status:** verified-live — the AOT section of the script is exercised on standard boots.

## Hotswap Dev Mode

The `hotswap-agent.jar` javaagent watches `build/live/classes/` for class-file changes. When `./gradlew hotswapFeed` recompiles, the agent retransforms affected classes in the running daemon. Semantics:
- Compile kills → reloads on next access. No daemon restart needed.
- The agent is optional: the daemon runs without it (warning printed).
- Class loading is one-directional: new classes load, removed classes stay loaded until the daemon restarts.

> **Status:** verified-live — the agent wiring is in `bin/oroboros-daemon:122-127`.

## macOS Service Install

A launchd plist exists at `bin/com.trikeshed.oroboros.plist`:

```bash
# Install
cp bin/com.trikeshed.oroboros.plist ~/Library/LaunchAgents/
# Edit TRIKESHED_HOME and TRIKESHED_REPO in the plist
launchctl load ~/Library/LaunchAgents/com.trikeshed.oroboros.plist

# Configure cycle interval
defaults write com.trikeshed.oroboros FLYWHEEL_CYCLE_INTERVAL -string "30m"
# Reload to pick up changes
launchctl unload ~/Library/LaunchAgents/com.trikeshed.oroboros.plist
launchctl load ~/Library/LaunchAgents/com.trikeshed.oroboros.plist

# Uninstall
launchctl unload ~/Library/LaunchAgents/com.trikeshed.oroboros.plist
rm ~/Library/LaunchAgents/com.trikeshed.oroboros.plist
```

> **Status:** verified-live — the plist source at `bin/com.trikeshed.oroboros.plist` is accuracy-checked against the script flags.

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `CAS blob missing for ...` | `classpath.tsv` references a blob not in `cas/sha256/` | Re-run Path A from a checkout to re-absorb, or `./gradlew hotswapFeed` |
| `AOT cache missing or empty` | Stale AOT file | Delete `~/.local/state/trikeshed/aot/oroboros-*.aot*` and reboot |
| `Address already in use` | Port 8888 occupied | Use a different `--home` or kill the other process. Note `bin/oroboros-daemon` is a wrapper: killing its PID leaves the `java` child holding the port. Kill by classpath instead — `ps aux \| grep '[b]uild/live/classes' \| awk '{print $2}' \| xargs kill` |
| Daemon serves an old version of your change | `build/live/classes` is stale | Run `./gradlew hotswapFeed` and restart; see step 1b |
| `no build dirs, no forge manifest` | Neither checkout build nor forge home available | Run `./gradlew jvmMainClasses` first |
