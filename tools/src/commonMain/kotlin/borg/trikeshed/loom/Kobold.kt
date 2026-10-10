package borg.trikeshed.loom

import borg.trikeshed.job.*
import borg.trikeshed.util.*

/**
 * loomctl `kobold`: offline, exact-target startup staging for the pinned upstream KoboldCpp image.
 * No network client or provider mutation is exposed here. Source snapshots and generated patches
 * may contain credentials: errors never expose them.
 */
object Kobold {
    const val MAX_SNAPSHOT_BYTES = 1_048_576

    enum class Target(val profile: String) {
        Default48("48gb-mtp"),
        Batch96("96gb-batch");

        fun template(): Map<String, Any> = Kobold.parse((if (this == Default48) DEFAULT else BATCH).encodeToByteArray())

        companion object {
            fun parse(profile: String): Target = entries.firstOrNull { it.profile == profile } ?: error("unknown launch profile")
        }
    }

    /** Holds sensitive inherited configuration; it has no toString of its values. */
    class PreparedOverride(
        val target: Target,
        val targetId: String,
        val original: Map<String, Any>,
        val inputSha256: String,
        val patch: Map<String, Any>,
        val rollback: Map<String, Any>,
    ) {
        /** Private staging metadata binds the complete exact input, not a projection. */
        fun manifest(): Map<String, Any> = mapOf(
            "format" to "loom-kobold-stage/1",
            "target_id" to targetId,
            "profile" to target.profile,
            "source_sha256" to inputSha256,
            "adapter_sha256" to digest(ADAPTER.encodeToByteArray()),
            "adapter_bytes" to ADAPTER.encodeToByteArray().size.toLong(),
            "args_sha256" to digest((patch["args"] as? String ?: "").encodeToByteArray()),
        )

        fun verifyPrecondition(fresh: ByteArray) {
            if (!same(parse(fresh), original)) error("complete endpoint snapshot changed; regenerate stage")
        }

        fun verifyReadback(readback: ByteArray) = verify(readback, patch)

        fun verifyRollback(readback: ByteArray) = verify(readback, rollback)

        fun verify(readback: ByteArray, patch: Map<String, Any>) {
            val actual = parse(readback)
            if (!same(actual["args"], patch["args"]) || !same(actual["env"], patch["env"])) {
                error("provider command or complete environment readback differs")
            }
            validateCommand(actual)
            // Command's deconstructed fields may be omitted by the current provider;
            // when returned they must agree with raw args, validated above.
            val command = listOf("args", "entrypoint", "cmd", "env")
            if (!same(actual - command, original - command)) error("unrelated provider configuration changed")
        }
    }

    fun digest(bytes: ByteArray): String = sha256(bytes).toLowerHex()

    /** Decode bounded JSON objects, rejecting duplicate keys at every depth. */
    fun parse(bytes: ByteArray): Map<String, Any> {
        if (bytes.size > MAX_SNAPSHOT_BYTES) error("protected JSON exceeds size limit")
        val value = json(bytes, unique = true) ?: error("invalid or ambiguous protected JSON")
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any> ?: error("protected JSON must be an object")
    }

    fun validateCommand(endpoint: Map<String, Any>) {
        val raw = endpoint["args"] as? String ?: error("exact original args string is required")
        val command = if (raw.isEmpty()) mapOf("entrypoint" to emptyList<Any>(), "cmd" to emptyList()) else parse(raw.encodeToByteArray())
        if (command.keys.any { it != "entrypoint" && it != "cmd" }) error("unknown original command field")
        for (k in listOf("entrypoint", "cmd")) {
            val expected = command[k] ?: emptyList<Any>()
            if (expected !is List<*> || !expected.all { it is String }) error("command must contain argument arrays")
            val actual = endpoint[k]
            if (actual != null && !same(actual, expected)) error("contradictory provider command representations")
        }
    }

    fun prepare(target: Target, targetId: String, input: ByteArray): PreparedOverride {
        val original = parse(input)
        val template = target.template()
        if (targetId.isEmpty() || targetId.length > 128 || !targetId.all { it in 'a'..'z' || it in '0'..'9' } || original["id"] != targetId) {
            error("unexpected endpoint identity")
        }
        for (field in listOf("name", "type", "image", "disk", "ports")) {
            if (!same(original[field], template[field])) error("endpoint does not match reviewed profile")
        }
        val gpu = original["gpu"] as? Map<*, *>
        for (field in listOf("pools", "excludedTypes", "count", "minCudaVersion")) {
            if (!same(gpu?.get(field), (template["gpu"] as? Map<*, *>)?.get(field))) error("endpoint GPU selection differs from reviewed profile")
        }
        if ((gpu?.get("allowedCudaVersions") as? List<*>)?.isEmpty() != true) error("unexpected CUDA version restriction")
        val volumes = original["networkVolumes"] as? List<*> ?: error("Xet stage requires one network volume")
        if (volumes.size != 1 ||
            (volumes[0] as? String)?.let { id -> id.isNotEmpty() && id.length <= 128 && id.all { it in 'a'..'z' || it in '0'..'9' } } != true
        ) error("Xet stage requires one network volume")
        val workers = original["workers"] as? Map<*, *>
        if (asU64(workers?.get("min")) != 0uL || asU64(workers?.get("max")) != 0uL) error("endpoint must already have zero worker capacity")
        validateCommand(original)
        @Suppress("UNCHECKED_CAST")
        val env = LinkedHashMap(original["env"] as? Map<String, Any> ?: error("environment must be a complete string map"))
        if (env.values.any { it !is String }) error("environment must contain only strings")
        val reviewedEnv = template["env"] as? Map<*, *>
        for (key in listOf("PORT", "PORT_HEALTH", "HEALTH_CHECK_PATH")) {
            if (!same(env[key], reviewedEnv?.get(key))) error("endpoint health configuration differs from reviewed profile")
        }
        val artifacts = parse(ARTIFACTS.encodeToByteArray())
        if (!same(artifacts["image"] ?: Null, original["image"] ?: Null) ||
            (artifacts["startup_adapter"] as? Map<*, *>)?.get("sha256") != digest(ADAPTER.encodeToByteArray())
        ) error("reviewed startup inputs have changed")
        val pins = artifacts["artifacts"] as? List<*> ?: error("artifact manifest is invalid")
        if (pins.size != 3) error("artifact manifest requires one complete bundle")
        for ((pin, slot) in pins.zip(listOf("RUNTIME" to "KCPP_BIN_OVERRIDE", "MODEL" to "KCPP_MODEL", "MMPROJ" to "KCPP_MMPROJ"))) {
            val (name, key) = slot
            val fields = pin as? Map<*, *>
            val hash = fields?.get("sha256") as? String ?: error("artifact hash is missing")
            val url = fields["url"] as? String ?: error("artifact URL is missing")
            val size = asU64(fields["bytes"])?.takeIf { it > 0uL } ?: error("artifact size is missing")
            if (fields["name"] != name || fields["url_env"] != key || !url.startsWith("https://") ||
                hash.length != 64 || !hash.all { it in '0'..'9' || it in 'a'..'f' }
            ) error("artifact manifest is invalid")
            env[key] = url
            env["LOOM_KCPP_${name}_SHA256"] = hash
            env["LOOM_KCPP_${name}_BYTES"] = size.toString()
        }
        env.remove("KCPP_ARGS")
        env["LOOM_KCPP_DOWNLOAD"] = "xet"
        env["LOOM_KCPP_CACHE_DIR"] = reviewedEnv?.get("LOOM_KCPP_CACHE_DIR") ?: Null
        val patch = mapOf("args" to jsonText(command(target)), "env" to env)
        val rollback = mapOf("args" to (original["args"] ?: Null), "env" to (original["env"] ?: Null))
        return PreparedOverride(target, targetId, original, digest(input), patch, rollback)
    }

    fun command(target: Target): Map<String, Any> {
        val hash = digest(ADAPTER.encodeToByteArray())
        val marker = "LOOM_ADAPTER_$hash"
        // Rust str::lines: split at \n, then a trailing \r is dropped.
        if (!ADAPTER.endsWith('\n') || ADAPTER.split('\n').any { it.removeSuffix("\r") == marker }) {
            error("adapter cannot be embedded without changing bytes")
        }
        val bootstrap = $$"""set -euo pipefail
umask 077
script=$(mktemp /tmp/loom-kcpp.XXXXXXXX)
trap 'rm -f -- "$script"' EXIT
cat > "$script" <<'$$marker'
$$ADAPTER$$marker
if command -v sha256sum >/dev/null 2>&1; then
  actual=$(sha256sum < "$script")
else
  actual=$(shasum -a 256 < "$script")
fi
[[ "${actual%% *}" == '$$hash' ]] || { printf 'LOOM startup adapter integrity failure\n' >&2; exit 1; }
exec 3< "$script"
rm -f -- "$script"
trap - EXIT
exec /bin/bash -p /dev/fd/3 $${target.profile}
"""
        return mapOf("entrypoint" to listOf("/bin/bash", "-p", "-c"), "cmd" to listOf(bootstrap))
    }

    // The reviewed inputs loomctl embeds with include_str!, byte for byte from cocaine-rats e4898291 deploy/.
    /** deploy/koboldcpp-verified-start.sh */
    const val ADAPTER = $$"""#!/bin/bash
# LOOM startup packaging; AGPL-3.0-only. Reuses the pinned upstream image.
# Trusted deployment configuration supplies the pinned URL/SHA256/length triples.
# Never invoke docker-helper.sh: it downloads and executes unchecked artifacts.
set -euo pipefail
umask 077
fail() { printf 'LOOM Kobold startup: %s\n' "$1" >&2; exit 1; }

[[ $# == 1 ]] || fail 'expected one launch profile'
(( BASH_VERSINFO[0] > 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] >= 3) )) \
    || fail 'protected startup requires Bash 4.3 or later'
host_os=$(uname -s)
[[ "$host_os" == Linux || "$host_os" == Darwin ]] || fail 'unsupported startup host'
[[ "$host_os" != Linux || "$EUID" -eq 0 ]] || fail 'protected Linux startup requires root'
metadata() {
    if [[ "$host_os" == Linux ]]; then
        stat -c '%u %a' -- "$1"
    else
        stat -f '%u %Lp' "$1"
    fi
}
protected_path() {
    local item="$1" pair owner mode
    while true; do
        [[ ! -L "$item" ]] || return 1
        pair=$(metadata "$item") || return 1
        read -r owner mode <<< "$pair"
        [[ "$owner" =~ ^[0-9]+$ && "$mode" =~ ^[0-7]{3,4}$ ]] || return 1
        (( (8#$mode & 022) == 0 )) || return 1
        if [[ "$host_os" == Linux ]]; then
            (( owner == 0 )) || return 1
        else
            (( owner == 0 || owner == EUID )) || return 1
        fi
        [[ "$item" == / ]] && break
        item="${item%/*}"
        [[ -n "$item" ]] || item=/
    done
}
case "$1" in
    48gb-mtp|96gb-batch) profile="$1" ;;
    *) fail 'expected one launch profile: 48gb-mtp or 96gb-batch' ;;
esac
case "$profile" in
    48gb-mtp) export LOOM_INGRESS_MAX_ACTIVE=1 ;;
    96gb-batch) export LOOM_INGRESS_MAX_ACTIVE=4 ;;
esac
[[ -z "${KCPP_ARGS:-}" ]] || fail 'KCPP_ARGS is unsupported; select a reviewed launch profile'
[[ "${PORT:-}" == 5001 && "${PORT_HEALTH:-}" == 5001 ]] \
    || fail 'protected ingress must own port and health port 5001'

# The only public listener is the Rust ingress. No fallback to direct Kobold is
# permitted when the protected binaries, key or host configuration are absent.
for name in ENVELOPE INGRESS; do
    path_key="LOOM_${name}_BIN_PATH" hash_key="LOOM_${name}_BIN_SHA256" size_key="LOOM_${name}_BIN_BYTES"
    path="${!path_key:-}" hash="${!hash_key:-}" size="${!size_key:-}"
    [[ "$path" == /* && "$hash" =~ ^[0-9a-f]{64}$ && "$size" =~ ^[1-9][0-9]*$ \
        && ${#size} -le 15 ]] || fail "invalid protected ${name,,} binary configuration"
done
[[ "${LOOM_INGRESS_MAC_KEY_FILE:-}" == /* && -f "$LOOM_INGRESS_MAC_KEY_FILE" \
    && ! -L "$LOOM_INGRESS_MAC_KEY_FILE" && -O "$LOOM_INGRESS_MAC_KEY_FILE" \
    && $(wc -c < "$LOOM_INGRESS_MAC_KEY_FILE") -eq 32 ]] \
    || fail 'protected ingress key file unavailable'
protected_path "$LOOM_INGRESS_MAC_KEY_FILE" || fail 'unsafe ingress key ancestry or ownership'
read -r _ key_mode <<< "$(metadata "$LOOM_INGRESS_MAC_KEY_FILE")"
(( (8#$key_mode & 077) == 0 )) || fail 'ingress key file must have owner-only mode'
[[ -n "${LOOM_INGRESS_ENDPOINT_ID:-}" && -n "${LOOM_INGRESS_EXPECTED_MODEL:-}" ]] \
    || fail 'protected ingress identity unavailable'
[[ "${LOOM_ENVELOPE_MEMORY_MAX_BYTES:-}" =~ ^[1-9][0-9]*$ \
    && "${LOOM_ENVELOPE_SCRATCH_BYTES:-}" =~ ^[1-9][0-9]*$ ]] \
    || fail 'protected envelope memory limits unavailable'
[[ ${#LOOM_ENVELOPE_MEMORY_MAX_BYTES} -le 15 && ${#LOOM_ENVELOPE_SCRATCH_BYTES} -le 15 ]] \
    || fail 'protected envelope memory limits invalid'

names=(RUNTIME MODEL MMPROJ)
url_keys=(KCPP_BIN_OVERRIDE KCPP_MODEL KCPP_MMPROJ)
urls=() hashes=() sizes=() filenames=() paths=()
# Validate every pin before any network request or local artifact mutation.
for i in 0 1 2; do
    hash_key="LOOM_KCPP_${names[$i]}_SHA256"
    size_key="LOOM_KCPP_${names[$i]}_BYTES"
    url_key="${url_keys[$i]}"
    hash="${!hash_key:-}" size="${!size_key:-}" url="${!url_key:-}"
    [[ "$hash" =~ ^[0-9a-f]{64}$ && "$size" =~ ^[1-9][0-9]*$ && ${#size} -le 15 && "$url" == https://?* ]] \
        || fail 'invalid artifact configuration: HTTPS URL, SHA256 and positive byte count required'
    filename="${url%%\?*}"
    filename="${filename##*/}"
    [[ "$filename" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
        || fail 'invalid artifact configuration: URL must have a plain file basename'
    [[ "$i" == 0 || "$filename" == *.gguf ]] \
        || fail 'invalid artifact configuration: model and projector must retain GGUF basenames'
    urls+=("$url") hashes+=("$hash") sizes+=("$size") filenames+=("$filename")
done
if command -v sha256sum >/dev/null 2>&1; then
    digest() { sha256sum < "$1"; }
elif command -v shasum >/dev/null 2>&1; then
    digest() { shasum -a 256 < "$1"; }
else
    fail 'SHA256 implementation unavailable'
fi
verify() {
    local file="$1" expected_hash="$2" expected_size="$3" actual actual_size
    [[ -f "$file" && ! -L "$file" ]] && protected_path "$file" || return 1
    actual_size=$(wc -c < "$file")
    [[ "$actual_size" -eq "$expected_size" ]] || return 1
    actual=$(digest "$file") || return 1
    [[ "${actual%% *}" == "$expected_hash" ]]
}
for name in ENVELOPE INGRESS; do
    path_key="LOOM_${name}_BIN_PATH" hash_key="LOOM_${name}_BIN_SHA256" size_key="LOOM_${name}_BIN_BYTES"
    verify "${!path_key}" "${!hash_key}" "${!size_key}" \
        || fail "protected ${name,,} binary verification failed"
done

cache="${LOOM_KCPP_CACHE_DIR:-/runpod-volume/loom-verified}"
transport="${LOOM_KCPP_DOWNLOAD:-xet}"
[[ "$transport" == xet || "$transport" == https ]] || fail 'unsupported model transport'
if [[ "$transport" == xet ]]; then
    [[ "$host_os" == Linux && $(uname -m) == x86_64 ]] \
        || fail 'Xet startup requires Linux x86_64'
    [[ "$cache" == /runpod-volume/* ]] && mountpoint -q /runpod-volume \
        || fail 'Xet startup requires the persistent /runpod-volume mount'
    [[ -x /usr/bin/setpriv && -x /usr/bin/timeout && -x /usr/bin/python3 ]] \
        || fail 'Xet privilege-drop or interpreter unavailable'
    /usr/bin/env -i PATH=/usr/bin:/bin /usr/bin/python3 -I -S \
        -c 'import sys; sys.exit(0 if sys.version_info[:2] == (3, 12) else 1)' \
        || fail 'locked Xet client requires CPython 3.12'
    [[ "${HF_HUB_DISABLE_XET:-0}" != 1 ]] || fail 'Xet must not be disabled'
    for i in 1 2; do
        [[ "${urls[$i]}" =~ ^https://huggingface.co/buckets/([^/]+/[^/]+)/resolve/([^/?]+)$ ]] \
            || fail 'Xet requires explicit Hugging Face bucket object URLs'
    done
fi
while [[ "$cache" != / && "$cache" == */ ]]; do cache="${cache%/}"; done
[[ "$cache" == /* && "$cache" != / && "$cache" != *//* \
    && "$cache/" != */../* && "$cache/" != */./* ]] \
    || fail 'cache must be a canonical absolute non-root directory'
[[ "$cache" =~ ^/[A-Za-z0-9_./-]+$ ]] || fail 'cache path is not safe for the protected config'
# Inspect all components, including a symlink spelled with a trailing slash.
# The configured parent is trusted deployment storage; tenant input never picks it.
parent="$cache"
while [[ "$parent" != / ]]; do
    [[ ! -L "$parent" ]] || fail 'cache must not contain symlink components'
    parent="${parent%/*}"
    [[ -n "$parent" ]] || parent=/
done
mkdir -p -- "$cache"
[[ -d "$cache" && ! -L "$cache" && -O "$cache" ]] \
    && protected_path "$cache" || fail 'cache must have protected ownership and ancestors'
chmod 700 "$cache"
staging=$(mktemp -d "$cache/.startup.XXXXXXXX")
trap 'rm -rf -- "$staging"' EXIT

# Only upstream wheel bytes identified below enter this client. Extraction uses
# isolated Python stdlib, without pip, build hooks, site packages or root-time
# third-party imports. The official hf CLI runs as a separate unprivileged UID
# with an empty inherited environment; it cannot read the root-only ingress key.
xet_client=''
ensure_xet_client() {
    [[ -z "$xet_client" ]] || return
    local wheel_cache="$cache/.hf-wheels-2.0.0-xet-1.6.0-py312" hash size url file wheel
    [[ ! -L "$wheel_cache" ]] || fail 'unsafe Xet wheel cache'
    mkdir -p -- "$wheel_cache"
    protected_path "$wheel_cache" || fail 'unsafe Xet wheel ownership'
    chmod 700 "$wheel_cache"
    xet_client="$staging/hf-client"
    mkdir -p -- "$xet_client"
    while read -r hash size url; do
        file="${url##*/}"
        wheel="$wheel_cache/$file"
        if [[ ! -e "$wheel" && ! -L "$wheel" ]]; then
            curl --disable --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
                --connect-timeout 30 --max-time 300 --retry 2 --max-filesize "$size" \
                --output "$staging/wheel" -- "$url" || fail 'Xet wheel download failed'
            verify "$staging/wheel" "$hash" "$size" || fail 'Xet wheel integrity failure'
            chmod 600 "$staging/wheel"
            mv -- "$staging/wheel" "$wheel"
        fi
        verify "$wheel" "$hash" "$size" || fail 'Xet wheel cache integrity failure'
        /usr/bin/env -i PATH=/usr/bin:/bin /usr/bin/python3 -I -S -m zipfile \
            -e "$wheel" "$xet_client" || fail 'Xet wheel extraction failed'
    done <<'LOOM_XET_WHEELS'
b3eecb60540c17e8ef94c187104f4a5c0c451afcb9510ba8e17739bedcc2d6dd 828498 https://files.pythonhosted.org/packages/5f/f9/d752d5756ce0405ba29405b65517b287d2baf9f77a1cf5329266f3d577f0/huggingface_hub-2.0.0-py3-none-any.whl
d62671bb130879cef0ee4c9ebe47a14af6c66ec53e6d84dc15936e5ffdfac82f 4464663 https://files.pythonhosted.org/packages/67/4e/a28359bf1c1ecf11eba22123168c138698f7cb576ac678f5a2e16cd5da08/hf_xet-1.6.0-cp38-abi3-manylinux2014_x86_64.manylinux_2_17_x86_64.whl
6152fdbbf9a77fdec97731721bebf7c4c44f7c29b424b0065826173efc7ed101 132079 https://files.pythonhosted.org/packages/12/b8/4bd346e22b28902df4d651910f5242c28d84e4a5c2435ca5c3f797ed7e2e/anyio-4.15.1-py3-none-any.whl
255bc9599cf7748b4b1a446ccc735421bd08a2ae529a8b88597d3de5664ee360 125251 https://files.pythonhosted.org/packages/58/50/6c0d534c5f134586a8e1ba4e330569e32f057e33372ae556463212fb4cd3/click-8.5.0-py3-none-any.whl
9b139fb93b2ac5807f7feaf63aa4546fbd74074a2d3f554c040f40a4694d7b9f 109077 https://files.pythonhosted.org/packages/a9/3b/b74ae9db2f78f1354278731372ae89870626be7b86cbb46b8d707acd1a98/filelock-4.0.6-py3-none-any.whl
8dd6e646e99ea382bd85f97a45e6b526a442d79423a7dc673f1e2756d05fcb5f 221738 https://files.pythonhosted.org/packages/6c/c0/a98505f18594f1bce828bb159cec0fcf9860562f1a2c85913409fc8f3d9e/fsspec-2026.9.0-py3-none-any.whl
63cf8bbe7522de3bf65932fda1d9c2772064ffb3dae62d55932da54b31cb6c86 37515 https://files.pythonhosted.org/packages/04/4b/29cac41a4d98d144bf5f6d33995617b185d14b22401f75ca86f384e87ff1/h11-0.16.0-py3-none-any.whl
e1e05d4f25f7d7d496bfb96748f6f4b67657b03da069b3a68c36069f3db73d0a 83423 https://files.pythonhosted.org/packages/09/ba/a4568248771ce81957bfb7cc600264a40fbcda092391ee1c415c50be4bea/httpcore2-2.13.1-py3-none-any.whl
6dff50fabc270ee5fd25d845d0b078ed20564579744d6d962850975996d2f9a4 95597 https://files.pythonhosted.org/packages/d8/9c/6fe8931fd9f381042a9e4c7d5a7b4cbf7016b252bec0c99a49fce42c3326/httpx2-2.13.1-py3-none-any.whl
ab7ae7122974553370f0bdb919e1a960b2cd1bc1ef0276416d896db81c14582c 69583 https://files.pythonhosted.org/packages/58/a2/bb081bab032533a855d44de1d56f8e8426114ff1ba5d1f07a438a0a654f8/idna-3.20-py3-none-any.whl
d7193f7c8e4e93f444fde0262bf90af30e16fa0ad0ad44cb553c87339b23cd1c 129956 https://files.pythonhosted.org/packages/63/34/ba1c580383c9eada3711951fef0795c80b829a078d72188184bcab9dd527/packaging-26.3-py3-none-any.whl
ba1cc08a7ccde2d2ec775841541641e4548226580ab850948cbfda66a1befcdc 807870 https://files.pythonhosted.org/packages/8b/9d/b3589d3877982d4f2329302ef98a8026e7f4443c765c46cfecc8858c6b4b/pyyaml-6.0.3-cp312-cp312-manylinux2014_x86_64.manylinux_2_17_x86_64.manylinux_2_28_x86_64.whl
c293e525e6fef9c20e8728fd4612df02a0aa31bb5fe91ecd93e123b1b7bffa73 80199 https://files.pythonhosted.org/packages/a7/03/921a3d3c75785aca9ebfbfcabfbc3a1be12e2ab5265deb026d55a5a3f83e/tqdm-4.70.1-py3-none-any.whl
adaeaecf1cbb5f4de3b1959b42d41f6fab57b2b1666adb59e89cb0b53361d981 18660 https://files.pythonhosted.org/packages/19/97/56608b2249fe206a67cd573bc93cd9896e1efb9e98bce9c163bcdc704b88/truststore-0.10.4-py3-none-any.whl
481caa481374e813c1b176ada14e97f1f67a4539ce9cfeb3f350d78d6370c2e8 45571 https://files.pythonhosted.org/packages/49/d3/b8441a820a491ddfc024b0b0cf0393375b75ea13866d9c66727e54c2fc80/typing_extensions-4.16.0-py3-none-any.whl
LOOM_XET_WHEELS
    # The extracted public code has no credentials. Model verification paths
    # remain root-only; only this temporary downloader workspace is writable.
    # zipfile extraction inherits umask 077; the separate downloader UID must
    # traverse package directories and read modules/native extensions.
    chmod -R a+rX "$xet_client"
    chmod 711 "$cache" "$staging"
}
xet_download() {
    local source="$1" destination="$2" uri work
    [[ "$source" =~ ^https://huggingface.co/buckets/([^/]+/[^/]+)/resolve/([^/?]+)$ ]] \
        || fail 'Xet requires an explicit Hugging Face bucket object URL'
    uri="hf://buckets/${BASH_REMATCH[1]}/${BASH_REMATCH[2]}"
    ensure_xet_client
    work=$(mktemp -d "$staging/xet.XXXXXXXX")
    chown 65531:65531 "$work"
    chmod 700 "$work"
    /usr/bin/timeout --signal=TERM --kill-after=5s 1800 \
        /usr/bin/setpriv --reuid=65531 --regid=65531 --clear-groups \
        --no-new-privs --bounding-set=-all --inh-caps=-all --ambient-caps=-all \
        /usr/bin/env -i PATH=/usr/bin:/bin PYTHONPATH="$xet_client" \
        PYTHONDONTWRITEBYTECODE=1 HF_HOME="$work/home" \
        HF_HUB_DISABLE_IMPLICIT_TOKEN=1 HF_HUB_DISABLE_UPDATE_CHECK=1 \
        HF_HUB_DISABLE_XET=0 HF_XET_CHUNK_CACHE_SIZE_BYTES=0 \
        /usr/bin/python3 -S -P -m huggingface_hub.cli.hf \
        buckets cp "$uri" "$work/artifact" || fail 'Xet bucket download failed'
    [[ -f "$work/artifact" && ! -L "$work/artifact" ]] || fail 'unsafe Xet download'
    mv -- "$work/artifact" "$destination"
    chown 0:0 "$destination"
    chmod 600 "$destination"
    rm -rf -- "$work"
}

for i in 0 1 2; do
    namespace="$cache/${hashes[$i]}"
    [[ ! -L "$namespace" && ( ! -e "$namespace" || -d "$namespace" ) ]] \
        || fail "${names[$i]} verification failed: unsafe cache namespace"
    mkdir -p -- "$namespace"
    protected_path "$namespace" || fail "${names[$i]} verification failed: unsafe cache namespace"
    chmod 700 "$namespace"
    target="$namespace/${filenames[$i]}"
    if [[ -e "$target" || -L "$target" ]]; then
        verify "$target" "${hashes[$i]}" "${sizes[$i]}" || fail "${names[$i]} verification failed in cache"
    else
        download="$staging/${names[$i]}"
        if [[ "$transport" == xet && "$i" != 0 ]]; then
            xet_download "${urls[$i]}" "$download"
        else
            curl --disable --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
                --connect-timeout 30 --max-time 1800 --retry 2 --retry-delay 2 \
                --max-filesize "${sizes[$i]}" --output "$download" -- "${urls[$i]}" \
                || fail "${names[$i]} download failed"
        fi
        verify "$download" "${hashes[$i]}" "${sizes[$i]}" || fail "${names[$i]} verification failed after download"
        chmod 600 "$download"
        mv -- "$download" "$target"
    fi
    paths+=("$target")
done
# The model-only cache is non-listable to the child; grant traversal and read
# access only to the three verified public artifacts. The Hugging Face client,
# private staging and metadata directories remain mode 0700.
for private in "$cache/.huggingface" "$cache/.hf-2.0.0-xet-1.6.0"; do
    if [[ -e "$private" || -L "$private" ]]; then
        [[ -d "$private" && ! -L "$private" && -O "$private" ]] \
            || fail 'unsafe private model-cache metadata'
        chmod 700 "$private"
    fi
done
for namespace in "$cache" "$cache/${hashes[0]}" "$cache/${hashes[1]}" "$cache/${hashes[2]}"; do
    [[ -d "$namespace" && ! -L "$namespace" && -O "$namespace" ]] \
        || fail 'unsafe public artifact namespace'
    chmod 711 "$namespace"
done
chmod 755 "${paths[0]}"
chmod 644 "${paths[1]}" "${paths[2]}"
rm -rf -- "$staging"
trap - EXIT

run_dir="${LOOM_ENVELOPE_RUN_DIR:-/run/loom}"
[[ "$run_dir" =~ ^/[A-Za-z0-9_./-]+$ && "$run_dir" != / && "$run_dir" != *//* \
    && "$run_dir/" != */../* && "$run_dir/" != */./* ]] \
    || fail 'invalid protected run directory'
parent="$run_dir"
while [[ "$parent" != / ]]; do
    [[ ! -L "$parent" ]] || fail 'protected run directory has a symlink component'
    parent="${parent%/*}"
    [[ -n "$parent" ]] || parent=/
done
mkdir -p -- "$run_dir"
[[ -d "$run_dir" && ! -L "$run_dir" && -O "$run_dir" ]] \
    || fail 'protected run directory unavailable'
chmod 700 "$run_dir"
protected_path "$run_dir" || fail 'unsafe protected run directory ancestry'
bin_dir=$(mktemp -d "$run_dir/bin.XXXXXXXX")
envelope_bin="$bin_dir/loom-envelope" ingress_bin="$bin_dir/loom-gpu-ingress"
config='' envelope_pid='' ingress_pid=''
stop_children() {
    trap - EXIT TERM INT
    [[ -z "$ingress_pid" ]] || kill "$ingress_pid" 2>/dev/null || true
    [[ -z "$envelope_pid" ]] || kill "$envelope_pid" 2>/dev/null || true
    [[ -z "$ingress_pid" ]] || wait "$ingress_pid" 2>/dev/null || true
    [[ -z "$envelope_pid" ]] || wait "$envelope_pid" 2>/dev/null || true
    [[ -z "$config" ]] || rm -f -- "$config"
    rm -f -- "$envelope_bin" "$ingress_bin"
    rmdir "$bin_dir" 2>/dev/null || true
}
trap stop_children EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
cp "$LOOM_ENVELOPE_BIN_PATH" "$envelope_bin"
cp "$LOOM_INGRESS_BIN_PATH" "$ingress_bin"
chmod 700 "$envelope_bin" "$ingress_bin"
verify "$envelope_bin" "$LOOM_ENVELOPE_BIN_SHA256" "$LOOM_ENVELOPE_BIN_BYTES" \
    || fail 'local envelope binary verification failed'
verify "$ingress_bin" "$LOOM_INGRESS_BIN_SHA256" "$LOOM_INGRESS_BIN_BYTES" \
    || fail 'local ingress binary verification failed'
config=$(mktemp "$run_dir/envelope.XXXXXXXX")
printf '{"cgroup_parent":"/sys/fs/cgroup/loom","runtime":"%s","model":"%s","projector":"%s","profile":"%s","uid":65532,"gid":65532,"listen_socket":"%s/kobold.sock","kobold_port":5002,"memory_max_bytes":%s,"scratch_bytes":%s,"connection_timeout_seconds":600}\n' \
    "${paths[0]}" "${paths[1]}" "${paths[2]}" "$profile" "$run_dir" \
    "$LOOM_ENVELOPE_MEMORY_MAX_BYTES" "$LOOM_ENVELOPE_SCRATCH_BYTES" > "$config"
chmod 600 "$config"

"$envelope_bin" serve "$config" &
envelope_pid=$!
LOOM_INGRESS_ENVELOPE_SOCKET="$run_dir/kobold.sock" "$ingress_bin" &
ingress_pid=$!
# Either process exiting ends the worker. /ping cannot become healthy until the
# ingress has proved the exact model over the protected Unix socket.
wait -n "$envelope_pid" "$ingress_pid" || true
fail 'protected GPU process exited'
"""
    /** deploy/koboldcpp-artifacts.json */
    const val ARTIFACTS = """{
  "schema_version": 1,
  "status": "staged_not_deployed",
  "image": "ghcr.io/lostruins/koboldcpp@sha256:eb8d30617b75674b36bea266dffbaa281e5473b3e462d7b0b564859c94093eb0",
  "platform": "linux/amd64",
  "runtime_bundle": {
    "format": "upstream-self-contained-executable",
    "python_native_pair": "one release asset; never mix a fork Python file with an upstream native library",
    "loomcache_supported": false
  },
  "startup_adapter": {
    "path": "deploy/koboldcpp-verified-start.sh",
    "sha256": "ac27a1e67251d3d190f381e3d385d4def6d22d0be98464ff5625d6f314d387cc",
    "download_transport": "Xet for bucket model/projector; verified HTTPS for upstream runtime; verified cached artifacts reused",
    "cache_path": "/runpod-volume/loom-verified",
    "xet_client": {
      "schema_version": 1,
      "platform": "linux/amd64",
      "python": "CPython 3.12",
      "huggingface_hub": "2.0.0",
      "hf_xet": "1.6.0",
      "compressed_wheels_bytes": 7249680,
      "wheels": [
        {
          "name": "huggingface-hub",
          "version": "2.0.0",
          "filename": "huggingface_hub-2.0.0-py3-none-any.whl",
          "url": "https://files.pythonhosted.org/packages/5f/f9/d752d5756ce0405ba29405b65517b287d2baf9f77a1cf5329266f3d577f0/huggingface_hub-2.0.0-py3-none-any.whl",
          "sha256": "b3eecb60540c17e8ef94c187104f4a5c0c451afcb9510ba8e17739bedcc2d6dd",
          "bytes": 828498
        },
        {
          "name": "hf-xet",
          "version": "1.6.0",
          "filename": "hf_xet-1.6.0-cp38-abi3-manylinux2014_x86_64.manylinux_2_17_x86_64.whl",
          "url": "https://files.pythonhosted.org/packages/67/4e/a28359bf1c1ecf11eba22123168c138698f7cb576ac678f5a2e16cd5da08/hf_xet-1.6.0-cp38-abi3-manylinux2014_x86_64.manylinux_2_17_x86_64.whl",
          "sha256": "d62671bb130879cef0ee4c9ebe47a14af6c66ec53e6d84dc15936e5ffdfac82f",
          "bytes": 4464663
        },
        {
          "name": "anyio",
          "version": "4.15.1",
          "filename": "anyio-4.15.1-py3-none-any.whl",
          "url": "https://files.pythonhosted.org/packages/12/b8/4bd346e22b28902df4d651910f5242c28d84e4a5c2435ca5c3f797ed7e2e/anyio-4.15.1-py3-none-any.whl",
          "sha256": "6152fdbbf9a77fdec97731721bebf7c4c44f7c29b424b0065826173efc7ed101",
          "bytes": 132079
        },
        {
          "name": "click",
          "version": "8.5.0",
          "filename": "click-8.5.0-py3-none-any.whl",
          "url": "https://files.pythonhosted.org/packages/58/50/6c0d534c5f134586a8e1ba4e330569e32f057e33372ae556463212fb4cd3/click-8.5.0-py3-none-any.whl",
          "sha256": "255bc9599cf7748b4b1a446ccc735421bd08a2ae529a8b88597d3de5664ee360",
          "bytes": 125251
        },
        {
          "name": "filelock",
          "version": "4.0.6",
          "filename": "filelock-4.0.6-py3-none-any.whl",
          "url": "https://files.pythonhosted.org/packages/a9/3b/b74ae9db2f78f1354278731372ae89870626be7b86cbb46b8d707acd1a98/filelock-4.0.6-py3-none-any.whl",
          "sha256": "9b139fb93b2ac5807f7feaf63aa4546fbd74074a2d3f554c040f40a4694d7b9f",
          "bytes": 109077
        },
        {
          "name": "fsspec",
          "version": "2026.9.0",
          "filename": "fsspec-2026.9.0-py3-none-any.whl",
          "url": "https://files.pythonhosted.org/packages/6c/c0/a98505f18594f1bce828bb159cec0fcf9860562f1a2c85913409fc8f3d9e/fsspec-2026.9.0-py3-none-any.whl",
          "sha256": "8dd6e646e99ea382bd85f97a45e6b526a442d79423a7dc673f1e2756d05fcb5f",
          "bytes": 221738
        },
        {
          "name": "h11",
          "version": "0.16.0",
          "filename": "h11-0.16.0-py3-none-any.whl",
          "url": "https://files.pythonhosted.org/packages/04/4b/29cac41a4d98d144bf5f6d33995617b185d14b22401f75ca86f384e87ff1/h11-0.16.0-py3-none-any.whl",
          "sha256": "63cf8bbe7522de3bf65932fda1d9c2772064ffb3dae62d55932da54b31cb6c86",
          "bytes": 37515
        },
        {
          "name": "httpcore2",
          "version": "2.13.1",
          "filename": "httpcore2-2.13.1-py3-none-any.whl",
          "url": "https://files.pythonhosted.org/packages/09/ba/a4568248771ce81957bfb7cc600264a40fbcda092391ee1c415c50be4bea/httpcore2-2.13.1-py3-none-any.whl",
          "sha256": "e1e05d4f25f7d7d496bfb96748f6f4b67657b03da069b3a68c36069f3db73d0a",
          "bytes": 83423
        },
        {
          "name": "httpx2",
          "version": "2.13.1",
          "filename": "httpx2-2.13.1-py3-none-any.whl",
          "url": "https://files.pythonhosted.org/packages/d8/9c/6fe8931fd9f381042a9e4c7d5a7b4cbf7016b252bec0c99a49fce42c3326/httpx2-2.13.1-py3-none-any.whl",
          "sha256": "6dff50fabc270ee5fd25d845d0b078ed20564579744d6d962850975996d2f9a4",
          "bytes": 95597
        },
        {
          "name": "idna",
          "version": "3.20",
          "filename": "idna-3.20-py3-none-any.whl",
          "url": "https://files.pythonhosted.org/packages/58/a2/bb081bab032533a855d44de1d56f8e8426114ff1ba5d1f07a438a0a654f8/idna-3.20-py3-none-any.whl",
          "sha256": "ab7ae7122974553370f0bdb919e1a960b2cd1bc1ef0276416d896db81c14582c",
          "bytes": 69583
        },
        {
          "name": "packaging",
          "version": "26.3",
          "filename": "packaging-26.3-py3-none-any.whl",
          "url": "https://files.pythonhosted.org/packages/63/34/ba1c580383c9eada3711951fef0795c80b829a078d72188184bcab9dd527/packaging-26.3-py3-none-any.whl",
          "sha256": "d7193f7c8e4e93f444fde0262bf90af30e16fa0ad0ad44cb553c87339b23cd1c",
          "bytes": 129956
        },
        {
          "name": "pyyaml",
          "version": "6.0.3",
          "filename": "pyyaml-6.0.3-cp312-cp312-manylinux2014_x86_64.manylinux_2_17_x86_64.manylinux_2_28_x86_64.whl",
          "url": "https://files.pythonhosted.org/packages/8b/9d/b3589d3877982d4f2329302ef98a8026e7f4443c765c46cfecc8858c6b4b/pyyaml-6.0.3-cp312-cp312-manylinux2014_x86_64.manylinux_2_17_x86_64.manylinux_2_28_x86_64.whl",
          "sha256": "ba1cc08a7ccde2d2ec775841541641e4548226580ab850948cbfda66a1befcdc",
          "bytes": 807870
        },
        {
          "name": "tqdm",
          "version": "4.70.1",
          "filename": "tqdm-4.70.1-py3-none-any.whl",
          "url": "https://files.pythonhosted.org/packages/a7/03/921a3d3c75785aca9ebfbfcabfbc3a1be12e2ab5265deb026d55a5a3f83e/tqdm-4.70.1-py3-none-any.whl",
          "sha256": "c293e525e6fef9c20e8728fd4612df02a0aa31bb5fe91ecd93e123b1b7bffa73",
          "bytes": 80199
        },
        {
          "name": "truststore",
          "version": "0.10.4",
          "filename": "truststore-0.10.4-py3-none-any.whl",
          "url": "https://files.pythonhosted.org/packages/19/97/56608b2249fe206a67cd573bc93cd9896e1efb9e98bce9c163bcdc704b88/truststore-0.10.4-py3-none-any.whl",
          "sha256": "adaeaecf1cbb5f4de3b1959b42d41f6fab57b2b1666adb59e89cb0b53361d981",
          "bytes": 18660
        },
        {
          "name": "typing-extensions",
          "version": "4.16.0",
          "filename": "typing_extensions-4.16.0-py3-none-any.whl",
          "url": "https://files.pythonhosted.org/packages/49/d3/b8441a820a491ddfc024b0b0cf0393375b75ea13866d9c66727e54c2fc80/typing_extensions-4.16.0-py3-none-any.whl",
          "sha256": "481caa481374e813c1b176ada14e97f1f67a4539ce9cfeb3f350d78d6370c2e8",
          "bytes": 45571
        }
      ]
    }
  },
  "launch_profiles": [
    "48gb-mtp",
    "96gb-batch"
  ],
  "artifacts": [
    {
      "name": "RUNTIME",
      "url_env": "KCPP_BIN_OVERRIDE",
      "url": "https://github.com/LostRuins/koboldcpp/releases/download/v1.122.1/koboldcpp-linux-x64",
      "sha256": "724b81ad4d0557e6cf6b1e634c1b973e9a56c0a99b5164a9cf1ecbb8c9ad53d8",
      "bytes": 641836648,
      "provenance": {
        "repository": "LostRuins/koboldcpp",
        "tag": "v1.122.1",
        "commit": "4959b8d3695cf740c1e2d508bedabdea70cbbd44",
        "github_release_asset_id": 592014670,
        "hash_source": "GitHub release asset digest; not a new local download"
      }
    },
    {
      "name": "MODEL",
      "url_env": "KCPP_MODEL",
      "url": "https://huggingface.co/buckets/jim8vsiwest/Qwen3.8-27B-TURBO-Fable-Cold-Fusion-735-882-Heretic-Uncensored-NEO-CODER-MAX-MTP-GGUF-bucket/resolve/Qwen3.8-27B-TurboFCFusion-735-882-Here-Uncen-NEO-CODER-MAX-MTP-Q8_0.gguf",
      "sha256": "54f27515edb20675f289f99b9c6d40d114fb634db21bae3fd4c901661aba85b9",
      "bytes": 30239020576,
      "provenance": {
        "repository": "DavidAU/Qwen3.8-27B-TURBO-Fable-Cold-Fusion-735-882-Heretic-Uncensored-NEO-CODER-MAX-MTP-GGUF",
        "commit": "ceb55042229d17dcf45df52ad84698339e63d4c5",
        "path": "Qwen3.8-27B-TurboFCFusion-735-882-Here-Uncen-NEO-CODER-MAX-MTP-Q8_0.gguf",
        "matching_bucket_xet_hash": "7b73ea30b421a991f552314babbb4caac669ade9e05b7117335874d4001971c4",
        "hash_source": "Immutable source Git LFS SHA256; bucket Xet hash and size match; not a local full-file hash"
      }
    },
    {
      "name": "MMPROJ",
      "url_env": "KCPP_MMPROJ",
      "url": "https://huggingface.co/buckets/jim8vsiwest/Qwen3.8-27B-TURBO-Fable-Cold-Fusion-735-882-Heretic-Uncensored-NEO-CODER-MAX-MTP-GGUF-bucket/resolve/mmproj-F16.gguf",
      "sha256": "82e620db8cb83267e9775e5aad3e8d8aa5af7ace825e94c52d3d730eb35af88a",
      "bytes": 927606976,
      "provenance": {
        "repository": "DavidAU/Qwen3.8-27B-TURBO-Fable-Cold-Fusion-735-882-Heretic-Uncensored-NEO-CODER-MAX-MTP-GGUF",
        "commit": "ceb55042229d17dcf45df52ad84698339e63d4c5",
        "path": "mmproj-F16.gguf",
        "matching_bucket_xet_hash": "6c4d2da9cfe6af5858c55b304c2a3a480574e4a9f2be39cfffe7de983ac8c59a",
        "hash_source": "Immutable source Git LFS SHA256; bucket Xet hash and size match; not a local full-file hash"
      }
    }
  ],
  "image_compressed_layers_bytes": 305051540
}
"""
    /** deploy/koboldcpp-serverless.json */
    const val DEFAULT = """{
  "name": "loom-twin-koboldcpp",
  "type": "LOAD_BALANCER",
  "image": "ghcr.io/lostruins/koboldcpp@sha256:eb8d30617b75674b36bea266dffbaa281e5473b3e462d7b0b564859c94093eb0",
  "gpu": {
    "pools": [
      "AMPERE_48",
      "ADA_48_PRO"
    ],
    "excludedTypes": [
      "NVIDIA RTX PRO 6000 Blackwell Server Edition MIG 2g.48gb"
    ],
    "count": 1,
    "minCudaVersion": "12.8"
  },
  "disk": 80,
  "ports": [
    "5001/http"
  ],
  "env": {
    "PORT": "5001",
    "PORT_HEALTH": "5001",
    "HEALTH_CHECK_PATH": "/ping",
    "KCPP_DONT_TUNNEL": "true",
    "KCPP_BIN_OVERRIDE": "https://github.com/LostRuins/koboldcpp/releases/download/v1.122.1/koboldcpp-linux-x64",
    "KCPP_MODEL": "https://huggingface.co/buckets/jim8vsiwest/Qwen3.8-27B-TURBO-Fable-Cold-Fusion-735-882-Heretic-Uncensored-NEO-CODER-MAX-MTP-GGUF-bucket/resolve/Qwen3.8-27B-TurboFCFusion-735-882-Here-Uncen-NEO-CODER-MAX-MTP-Q8_0.gguf",
    "KCPP_MMPROJ": "https://huggingface.co/buckets/jim8vsiwest/Qwen3.8-27B-TURBO-Fable-Cold-Fusion-735-882-Heretic-Uncensored-NEO-CODER-MAX-MTP-GGUF-bucket/resolve/mmproj-F16.gguf",
    "KCPP_ARGS": "--host 0.0.0.0 --port 5001 --usecuda mmq --gpulayers 999 --contextsize 16384 --flashattention --quantkv q8_0 --usemtp --smartcontext --multiuser 5",
    "LOOM_KCPP_DOWNLOAD": "xet",
    "LOOM_KCPP_CACHE_DIR": "/runpod-volume/loom-verified"
  },
  "workers": {
    "min": 0,
    "max": 1,
    "idleTimeout": 5
  },
  "scaling": {
    "type": "REQUEST_COUNT",
    "requestCount": 1
  },
  "flashboot": "FLASHBOOT",
  "timeout": 330000,
  "networkVolumes": [
    "q78oep2r00"
  ],
  "dataCenterIds": [
    "US-IL-1"
  ]
}
"""
    /** deploy/koboldcpp-serverless-96gb-batch-test.json */
    const val BATCH = """{
  "name": "loom-twin-koboldcpp-96gb-batch-test",
  "type": "LOAD_BALANCER",
  "image": "ghcr.io/lostruins/koboldcpp@sha256:eb8d30617b75674b36bea266dffbaa281e5473b3e462d7b0b564859c94093eb0",
  "gpu": {
    "pools": [
      "BLACKWELL_96"
    ],
    "excludedTypes": [
      "NVIDIA RTX PRO 6000 Blackwell Max-Q Workstation Edition",
      "NVIDIA RTX PRO 6000 Blackwell Workstation Edition"
    ],
    "count": 1,
    "minCudaVersion": "13.0"
  },
  "disk": 80,
  "ports": [
    "5001/http"
  ],
  "env": {
    "PORT": "5001",
    "PORT_HEALTH": "5001",
    "HEALTH_CHECK_PATH": "/ping",
    "KCPP_DONT_TUNNEL": "true",
    "KCPP_BIN_OVERRIDE": "https://github.com/LostRuins/koboldcpp/releases/download/v1.122.1/koboldcpp-linux-x64",
    "KCPP_MODEL": "https://huggingface.co/buckets/jim8vsiwest/Qwen3.8-27B-TURBO-Fable-Cold-Fusion-735-882-Heretic-Uncensored-NEO-CODER-MAX-MTP-GGUF-bucket/resolve/Qwen3.8-27B-TurboFCFusion-735-882-Here-Uncen-NEO-CODER-MAX-MTP-Q8_0.gguf",
    "KCPP_MMPROJ": "https://huggingface.co/buckets/jim8vsiwest/Qwen3.8-27B-TURBO-Fable-Cold-Fusion-735-882-Heretic-Uncensored-NEO-CODER-MAX-MTP-GGUF-bucket/resolve/mmproj-F16.gguf",
    "KCPP_ARGS": "--host 0.0.0.0 --port 5001 --usecuda mmq --gpulayers 999 --contextsize 16384 --flashattention --quantkv q8_0 --noshift --parallelrequests 4 --multiuser 5 --batchsize 512 --ubatchsize 512",
    "LOOM_KCPP_DOWNLOAD": "xet",
    "LOOM_KCPP_CACHE_DIR": "/runpod-volume/loom-verified"
  },
  "workers": {
    "min": 0,
    "max": 0,
    "idleTimeout": 5
  },
  "scaling": {
    "type": "REQUEST_COUNT",
    "requestCount": 4
  },
  "flashboot": "FLASHBOOT",
  "timeout": 330000,
  "networkVolumes": [
    "jppxlbbl81"
  ],
  "dataCenterIds": [
    "US-NE-1"
  ]
}
"""
}
