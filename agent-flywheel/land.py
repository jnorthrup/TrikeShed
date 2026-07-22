#!/usr/bin/env python3
"""land.py — TDD/test gate for one incoming patch.

Usage:
  ./land.py REPO_PATH BRANCH PATCH_FILE [--test CMD] [--commit MSG] [--no-apply]

Override test detection: FLYWHEEL_TEST_CMD env, [tool.flywheel] test
in pyproject.toml, package.json scripts.test, or auto-probe (pytest /
cargo / go / make). Override main branch via FLYWHEEL_MAIN (default
auto-detect main → master → trunk).
"""
import argparse, json, os, re, subprocess, sys


def failure_signatures(output: str) -> set[str]:
    signatures = set()
    for raw in output.splitlines():
        line = raw.strip()
        if (line.startswith(("e:", "error:", "FAILED ", "FAILURE:"))
                or (line.startswith("> Task ") and line.endswith(" FAILED"))):
            signatures.add(re.sub(r"\s+", " ", line))
    return signatures


def is_nonregression(baseline_code: int, baseline_output: str,
                     candidate_code: int, candidate_output: str) -> bool:
    return candidate_code == 0


def patch_policy(patch_text: str) -> tuple[bool, str]:
    per_file: dict[str, list[int]] = {}
    current_file = "<unknown>"
    placeholder_patterns = (
        re.compile(r"\bstub(?:bed)?\b.*\b(?:compile|implementation|out)\b", re.I),
        re.compile(r"\b(?:fail|error|todo)\s*\(\s*['\"](?:not implemented|stub|placeholder)", re.I),
        re.compile(r"\b(?:NotImplementedError|UnsupportedOperationException)\s*\(", re.I),
        re.compile(r"\bTODO\s*\(\s*\)", re.I),
    )

    for line in patch_text.splitlines():
        if line.startswith("diff --git "):
            parts = line.split()
            current_file = parts[2][2:] if len(parts) >= 3 else "<unknown>"
            if current_file.lower().endswith((".diff", ".patch", ".orig", ".rej")):
                return False, f"patch/backup artifact added: {current_file}"
            per_file.setdefault(current_file, [0, 0])
            continue
        if line.startswith("+") and not line.startswith("+++"):
            per_file.setdefault(current_file, [0, 0])[0] += 1
            added = line[1:].strip()
            if any(pattern.search(added) for pattern in placeholder_patterns):
                return False, f"placeholder compile-fix code in {current_file}: {added[:160]}"
            production_path = "test" not in current_file.lower()
            if production_path and (
                    re.search(r"\bfun\b.*\{\s*\}\s*$", added)
                    or re.search(r"=\s*empty(?:List|Map|Set|Array)\s*\(", added)):
                return False, f"no-op production implementation in {current_file}: {added[:160]}"
        elif line.startswith("-") and not line.startswith("---"):
            per_file.setdefault(current_file, [0, 0])[1] += 1

    total_added = sum(counts[0] for counts in per_file.values())
    total_deleted = sum(counts[1] for counts in per_file.values())
    for path, (added, deleted) in per_file.items():
        if deleted >= 80 and deleted > max(added * 2, added + 60):
            return False, (f"deletion-dominant patch in {path}: "
                           f"added={added} deleted={deleted}")
    if total_deleted >= 150 and total_deleted > total_added * 2:
        return False, (f"deletion-dominant patch overall: "
                       f"added={total_added} deleted={total_deleted}")
    return True, "ok"


def restore_main(repo: str, main_branch: str, branch: str) -> None:
    subprocess.run(["git", "-C", repo, "merge", "--abort"],
                   capture_output=True, text=True)
    subprocess.run(["git", "-C", repo, "reset", "--hard"],
                   capture_output=True, text=True)
    subprocess.run(["git", "-C", repo, "checkout", main_branch],
                   capture_output=True, text=True)
    subprocess.run(["git", "-C", repo, "branch", "-D", branch],
                   capture_output=True, text=True)

def detect_test_cmd(repo: str) -> str:
    env = os.environ.get("FLYWHEEL_TEST_CMD", "").strip()
    if env:
        return env
    pp = os.path.join(repo, "pyproject.toml")
    if os.path.exists(pp):
        try:
            content = open(pp).read()
            if "flywheel" in content:
                for line in content.splitlines():
                    line = line.strip()
                    if line.startswith("test") and "=" in line:
                        _, _, val = line.partition("=")
                        v = val.strip().strip('"').strip("'")
                        if v:
                            return v
        except Exception:
            pass
    pj = os.path.join(repo, "package.json")
    if os.path.exists(pj):
        try:
            data = json.load(open(pj))
            t = data.get("scripts", {}).get("test", "")
            if t and "Error: no test specified" not in t:
                return f"npm test --silent -- {t}"
        except Exception:
            pass
    gradlew = os.path.join(repo, "gradlew")
    if os.path.exists(gradlew):
        build_text = ""
        for build_name in ("build.gradle.kts", "build.gradle"):
            build_path = os.path.join(repo, build_name)
            if os.path.exists(build_path):
                with open(build_path) as build_file:
                    build_text = build_file.read()
                break
        if ("kotlin(\"multiplatform\")" in build_text
                or "org.jetbrains.kotlin.multiplatform" in build_text):
            return "./gradlew jvmTest"
        return "./gradlew test"
    for probe, cmd in [
        ("pytest", "python -m pytest -x -q"),
        ("poetry.lock", "poetry run pytest -x -q"),
        ("Cargo.toml", "cargo test --quiet"),
        ("go.mod", "go test ./..."),
        ("Makefile", "make test"),
        ("node_modules", "npm test --silent"),
    ]:
        if os.path.exists(os.path.join(repo, probe)):
            return cmd
    return ""

def detect_main(repo: str) -> str:
    env = os.environ.get("FLYWHEEL_MAIN", "").strip()
    if env:
        return env
    for c in ("main", "master", "trunk"):
        p = subprocess.run(["git", "-C", repo, "rev-parse", "--verify", c],
                           capture_output=True, text=True)
        if p.returncode == 0:
            return c
    return "main"

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("repo"); ap.add_argument("branch"); ap.add_argument("patch_file")
    ap.add_argument("--test", default=""); ap.add_argument("--commit", default="")
    ap.add_argument("--no-apply", action="store_true")
    args = ap.parse_args()

    main_branch = detect_main(args.repo)
    test_cmd = args.test or detect_test_cmd(args.repo)
    patch_text = ""
    if not args.no_apply:
        with open(args.patch_file) as patch_file:
            patch_text = patch_file.read()
        policy_ok, policy_reason = patch_policy(patch_text)
        if not policy_ok:
            print(json.dumps({"ok": False, "stage": "policy",
                              "detail": policy_reason}))
            return 8

    baseline_code = 0
    baseline_output = ""
    if test_cmd:
        baseline = subprocess.run(test_cmd, shell=True, cwd=args.repo,
                                  capture_output=True, text=True, timeout=900)
        baseline_code = baseline.returncode
        baseline_output = baseline.stdout + baseline.stderr

    co = subprocess.run(["git", "-C", args.repo, "checkout", "-B",
                         args.branch, main_branch], capture_output=True, text=True)
    if co.returncode != 0:
        print(json.dumps({"ok": False, "stage": "checkout",
                          "detail": co.stderr.strip()[:500]}))
        return 2

    if not args.no_apply:
        ap_res = subprocess.run(["git", "-C", args.repo, "apply", "--3way", "-"],
                                input=patch_text, capture_output=True, text=True)
        if ap_res.returncode != 0:
            restore_main(args.repo, main_branch, args.branch)
            print(json.dumps({"ok": False, "stage": "apply",
                              "detail": ap_res.stderr.strip()[:1000]}))
            return 3

    if not test_cmd:
        print(json.dumps({"ok": True, "stage": "no-test",
                          "detail": "no test command discovered"}))
    else:
        t_res = subprocess.run(test_cmd, shell=True, cwd=args.repo,
                               capture_output=True, text=True, timeout=900)
        candidate_output = t_res.stdout + t_res.stderr
        if not is_nonregression(baseline_code, baseline_output,
                                t_res.returncode, candidate_output):
            new_failures = sorted(failure_signatures(candidate_output)
                                  - failure_signatures(baseline_output))
            restore_main(args.repo, main_branch, args.branch)
            tail = candidate_output[-2000:]
            print(json.dumps({"ok": False, "stage": "test",
                              "detail": (f"cmd={test_cmd}\n"
                                         f"new_failures={new_failures[:20]}\n{tail}")}))
            return 4

    subprocess.run(["git", "-C", args.repo, "add", "-A"], capture_output=True, text=True)
    msg = args.commit or f"flywheel: {args.branch}"
    cm = subprocess.run(["git", "-C", args.repo, "commit", "-m", msg],
                        capture_output=True, text=True)
    if cm.returncode != 0:
        restore_main(args.repo, main_branch, args.branch)
        print(json.dumps({"ok": False, "stage": "commit",
                          "detail": cm.stderr.strip()[:500]}))
        return 5

    subprocess.run(["git", "-C", args.repo, "checkout", main_branch],
                   capture_output=True, text=True)
    mg = subprocess.run(["git", "-C", args.repo, "merge", "--no-ff",
                         args.branch, "-m", f"land {args.branch}"],
                        capture_output=True, text=True)
    if mg.returncode != 0:
        restore_main(args.repo, main_branch, args.branch)
        print(json.dumps({"ok": False, "stage": "merge",
                          "detail": mg.stderr.strip()[:500]}))
        return 6

    ps = subprocess.run(["git", "-C", args.repo, "push", "origin", main_branch],
                        capture_output=True, text=True)
    if ps.returncode != 0:
        print(json.dumps({"ok": False, "stage": "push",
                          "detail": ps.stderr.strip()[:500]}))
        return 7

    subprocess.run(["git", "-C", args.repo, "branch", "-D", args.branch],
                   capture_output=True, text=True)
    print(json.dumps({"ok": True, "stage": "pushed",
                      "detail": f"landed {args.branch} on {main_branch}"}))
    return 0

if __name__ == "__main__":
    sys.exit(main())
