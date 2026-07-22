#!/usr/bin/env python3
"""snapshot.sh — dump repo shape for the flywheel's RESEARCHER role.

Usage: ./snapshot.sh [REPO_PATH]
Output: markdown summary on stdout. Default probes are git-native.
Override: set FLYWHEEL_SNAPSHOT=path/to/custom.sh — that runs instead.
"""
import os, subprocess, sys

REPO = sys.argv[1] if len(sys.argv) > 1 else "."

def run(*args, default="<empty>") -> str:
    p = subprocess.run(args, cwd=REPO, capture_output=True, text=True)
    out = (p.stdout or "").strip()
    return out if out else default

def main() -> int:
    if os.environ.get("FLYWHEEL_SNAPSHOT"):
        return subprocess.call([os.environ["FLYWHEEL_SNAPSHOT"], REPO])

    head = run("git", "-C", REPO, "rev-parse", "--verify", "HEAD",
               default="<no git>")
    if head == "<no git>":
        print("# Repo: <no git>")
        print("## ls -la\n" + run("ls", "-la", REPO))
        return 0

    print(f"# Repo HEAD: {head[:12]}")
    print()
    print("## git log --oneline -20")
    print(run("git", "-C", REPO, "log", "--oneline", "-20"))
    print()
    print("## git diff --stat HEAD~5..HEAD")
    print(run("git", "-C", REPO, "diff", "--stat", "HEAD~5..HEAD"))
    print()
    print("## tree (git ls-tree -r --name-only, first 80)")
    tree = run("git", "-C", REPO, "ls-tree", "-r", "--name-only", "HEAD")
    print("\n".join(tree.splitlines()[:80]))
    print()
    print("## TODO/FIXME markers (first 40)")
    todo = subprocess.run(
        ["git", "-C", REPO, "grep", "-nE", "TODO|FIXME|XXX|HACK",
         "--", "*.py", "*.ts", "*.go", "*.rs", "*.kt", "*.kts",
         "*.js", "*.mjs", "*.java", "*.c", "*.cpp", "*.h"],
        capture_output=True, text=True)
    if todo.returncode == 0:
        print("\n".join(todo.stdout.splitlines()[:40]))
    else:
        print("<none>")
    print()
    print("## doc/ work pool (unchecked [ ] items)")
    for docpath in ("doc/todo.md", "TODO.md", "doc/Todo.md"):
        full = os.path.join(REPO, docpath)
        if os.path.exists(full):
            try:
                lines = open(full).read().splitlines()
            except Exception:
                continue
            unchecked = [l for l in lines if l.strip().startswith("- [ ]")]
            print(f"### {docpath} — {len(unchecked)} unchecked items")
            for l in unchecked:
                print(l)
            break
    print()
    print("## build/test manifest probes")
    for probe in ("pytest.ini", "pyproject.toml", "package.json",
                  "Cargo.toml", "go.mod", "build.gradle.kts",
                  "build.gradle", "Makefile"):
        mark = "✓" if os.path.exists(os.path.join(REPO, probe)) else "✗"
        print(f"  {mark} {probe}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
