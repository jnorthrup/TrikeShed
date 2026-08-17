#!/bin/bash
set -e

echo "=== Repository Vitals ==="
echo "Commits: $(git rev-list --count HEAD)"
echo "Kotlin files (src/): $(git ls-tree -r HEAD | grep -c 'src/.*\.kt$')"
echo "Lines of code (src/): $(git ls-tree -r HEAD --name-only | grep '^src/.*\.kt$' | xargs -I{} git show HEAD:{} | wc -l)"
echo "Test files: $(git ls-tree -r HEAD | grep -c 'Test\.kt$')"
echo "Project age (First commit): $(git log --reverse --format=%ci | head -1)"
