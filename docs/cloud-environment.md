# Cloud environment handoff

The userspace Btrfs implementation is preserved in commit
[`8949dc914`](https://github.com/jnorthrup/TrikeShed/commit/8949dc91486cf60297636f8f36c308892bec6284).
It was merged into `master` and its Gradle integration curated in
[`fc9b24a7e`](https://github.com/jnorthrup/TrikeShed/commit/fc9b24a7e).
See [userspace Btrfs](btrfs-userspace.md) for commands and the supported boundary.

## Preserved validation

Validation below belongs to `8949dc914`, not to subsequent changes on `master`:

- JVM main and test source compilation completed without source/test exclusions.
- `btrfsStorageCheck`: 292 tests, 291 passed, one failed, no errors or skips.
  All 189 Btrfs tests passed. The remaining failure is
  `TrikeShedGraalVfsTest.graalPythonExplicitUtimeRetainsNanoseconds`:
  pinned GraalPy 25.3.4.1 drops nanoseconds before invoking the VFS.
- Native `btrfs check --readonly --check-data-csum` and `btrfs restore` passed
  after Zstd-to-LZ4 metadata conversion, member addition, and RAID1 conversion.
  The original file, CLI retrieval, and native restore had identical bytes.
- The Gradle `btrfs` CLI task completed successfully against the migrated state.

The cloud host was Linux x86_64. Actual macOS mounting remains unverified;
macFUSE ABI callback tests do not establish a working macOS mount. Complete
`btrfs-progs` parity remains outside the implemented boundary. The default
browser packaging previously received proxy CONNECT 403 for
`registry.npmmirror.com`; it and the Oroboros daemon were not validated.
The two missing `providerTag` imports diagnosed during onboarding were fixed
in the implementation commit, so the original startup note's compile blocker
and "no tests executed" status are obsolete.

## Reproduce the setup

Use an existing checkout at `/workspace/TrikeShed`. Each cloud task is isolated;
do not create a Git worktree unless explicitly requested. Read `AGENTS.md` and
`PRELOAD.md` before development. The following preserves the installer exercised
during onboarding, including a successful repeat run. It requires Linux x86_64,
Bash, Python 3, Git, curl, tar, sha256sum, and a writable `/workspace`.
It installs GraalVM JDK 25.0.4 outside the checkout and uses the repository's
Gradle wrapper (9.8.2 in the validated revision). Node 24.16 and Yarn 1.22.22
were installed by the Gradle setup tasks at that revision. A later checkout's
build configuration may require different tasks or dependencies.

TLS/checksum verification stays enabled. Proxy host/port settings and the public
proxy CA come from the current environment; no credentials or machine-specific
trust store are stored in Git. Required downloads include Oracle, Gradle,
Maven repositories, GitHub, and the Node/Yarn resources selected by the build.
Browser packaging also needs the registry hosts in the tracked lockfiles.

Run this block in Bash. It generates local helpers outside the checkout:

```bash
#!/usr/bin/env bash
set -euo pipefail

# Use the existing isolated checkout. Do not create worktrees or alter tracked files.
trikeshed_root=/workspace/TrikeShed
trikeshed_setup=/workspace/.trikeshed-cloud
trikeshed_jdk="$trikeshed_setup/toolchains/graalvm-25"
test -f "$trikeshed_root/gradlew"
test "$(uname -sm)" = 'Linux x86_64'
mkdir -p "$trikeshed_setup"/{downloads,toolchains,trust,gradle,home,logs,yarn-cache,npm-cache}

if [ ! -x "$trikeshed_jdk/bin/java" ]; then
    trikeshed_archive="$trikeshed_setup/downloads/graalvm-jdk-25_linux-x64_bin.tar.gz"
    curl -fL --retry 2 --connect-timeout 20 --max-time 300 \
        https://download.oracle.com/graalvm/25/archive/graalvm-jdk-25.0.4_linux-x64_bin.tar.gz \
        -o "$trikeshed_archive"
    # SHA-256 published by Oracle for the versioned archive, verified during onboarding.
    printf '%s  %s\n' 76007c309f821aaf435bce63162ea0395587fc77350801c81643fe7feea37276 "$trikeshed_archive" | sha256sum -c -
    mkdir -p "$trikeshed_jdk"
    tar -xzf "$trikeshed_archive" -C "$trikeshed_jdk" --strip-components=1
fi
"$trikeshed_jdk/bin/java" -version

# Retain the JDK's public roots and add the platform-provided public proxy CA.
trikeshed_trust=$(mktemp "$trikeshed_setup/trust/cacerts.XXXXXX")
cp "$trikeshed_jdk/lib/security/cacerts" "$trikeshed_trust"
if [ -n "${CODEX_PROXY_CERT:-}" ]; then
    "$trikeshed_jdk/bin/keytool" -importcert -noprompt -alias codex-platform-proxy \
        -file "$CODEX_PROXY_CERT" -keystore "$trikeshed_trust" -storepass changeit
fi
mv "$trikeshed_trust" "$trikeshed_setup/trust/cacerts"

cat > "$trikeshed_setup/activate.sh" <<'TRIKESHED_ACTIVATE'
export JAVA_HOME=/workspace/.trikeshed-cloud/toolchains/graalvm-25
export GRADLE_USER_HOME=/workspace/.trikeshed-cloud/gradle
export KONAN_DATA_DIR=/workspace/.trikeshed-cloud/konan
export YARN_CACHE_FOLDER=/workspace/.trikeshed-cloud/yarn-cache
export npm_config_cache=/workspace/.trikeshed-cloud/npm-cache
export PATH="$JAVA_HOME/bin:$PATH"
export TRIKESHED_AIRGAP=1
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Duser.home=/workspace/.trikeshed-cloud/home -Djavax.net.ssl.trustStore=/workspace/.trikeshed-cloud/trust/cacerts"
export JAVA_OPTS="${JAVA_OPTS:+$JAVA_OPTS }$(python3 - <<'PY'
import os, re
from urllib.parse import urlsplit
options=[]
for scheme in ('http','https'):
    proxy=os.environ.get(scheme.upper()+'_PROXY') or os.environ.get(scheme+'_proxy')
    if proxy:
        parsed=urlsplit(proxy)
        assert parsed.hostname and re.fullmatch(r'[A-Za-z0-9.-]+', parsed.hostname)
        options.extend([f'-D{scheme}.proxyHost={parsed.hostname}',f'-D{scheme}.proxyPort={parsed.port or 80}'])
options.extend(['-Dhttp.nonProxyHosts=localhost|127.*|[::1]'])
print(' '.join(options))
PY
)"
TRIKESHED_ACTIVATE

cat > "$trikeshed_setup/resolve-jvm.init.gradle" <<'TRIKESHED_RESOLVE'
gradle.projectsEvaluated {
    rootProject.tasks.register('cloudResolveJvmDependencies') {
        doLast {
            ['jvmCompileClasspath', 'jvmRuntimeClasspath', 'jvmTestRuntimeClasspath'].each { name ->
                def files = rootProject.configurations.getByName(name).resolve()
                println("Cached ${name}: ${files.size()} artifacts")
            }
        }
    }
}
TRIKESHED_RESOLVE

source "$trikeshed_setup/activate.sh"
cd "$trikeshed_root"
git submodule update --init --recursive utils/hermes-trikeshed-graalpy
./gradlew --version
./gradlew -I "$trikeshed_setup/resolve-jvm.init.gradle" cloudResolveJvmDependencies \
    fetchSumoCorpus fetchSumoFullCorpus kotlinNodeJsSetup kotlinYarnSetup --console=plain --max-workers=4
git status --short
```

Install `btrfs-progs` through a trusted package manager for native interoperability
checks; version 6.14 was used in the validated run. Both `btrfs` and `mkfs.btrfs`
must be runnable on `PATH`. Native codec interoperability checks also require
the platform's libzstd and liblz4 shared libraries. These are test dependencies;
the common Kotlin codecs do not call them for normal operation.

On the original cloud machine, tools were installed under
`/workspace/.trikeshed-tools/bin` with wrappers for extracted Debian packages.
Those local binary paths are not portable installation instructions. Install the
tools on the destination machine, or add that directory to `PATH` only when
resuming the retained cloud filesystem.

Activate once in each fresh Bash shell, then run the storage checks:

```bash
source /workspace/.trikeshed-cloud/activate.sh
cd /workspace/TrikeShed
export TRIKESHED_BTRFS_PROGS="$(command -v btrfs)"
command -v mkfs.btrfs
./gradlew -PkotlinJsApps= :btrfsStorageCheck --console=plain --max-workers=4
```

At the validated revision, `-PkotlinJsApps=` selected storage development without
browser application packaging; it did not exclude Kotlin source or test files.
Inspect `build/test-results/btrfsStorageCheck/TEST-*.xml` for executed counts and
failures. The known GraalPy failure above means the full task's exit status is
nonzero. Do not treat missing native dependencies or skipped checks as native
compatibility evidence. No long-running service is required for this workflow.

The installer, activation instructions, validation evidence, and feature limits
are preserved here so continuation does not depend on this cloud machine.
Downloaded toolchains, caches, logs, and synthetic test images are reproducible
local artifacts and are not source files. Live processes and injected GitHub
credentials do not transfer through Git. This handoff does not shut down or
delete the cloud environment.
