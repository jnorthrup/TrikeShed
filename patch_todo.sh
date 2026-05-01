#!/bin/bash
sed -i 's/- \[ \] Fix `macosMain` TODO stub/- [x] Fix `macosMain` TODO stub/g' libs/cpu-cache/todo.md
sed -i 's/- \[ \] Fix `linuxMain` TODO stub/- [x] Fix `linuxMain` TODO stub/g' libs/cpu-cache/todo.md
sed -i 's/- \[ \] Remove unused imports in native actuals/- [x] Remove unused imports in native actuals/g' libs/cpu-cache/todo.md
sed -i 's/- \[ \] Remove or repurpose dead `nativeMain` actual/- [x] Remove or repurpose dead `nativeMain` actual/g' libs/cpu-cache/todo.md
sed -i 's/- \[ \] Add macOS native `sysctl` interop for cache sizes/- [x] Add macOS native `sysctl` interop for cache sizes/g' libs/cpu-cache/todo.md
sed -i 's/- \[ \] Add `commonTest` for `CpuCacheTopology` and `toConfix()`/- [x] Add `commonTest` for `CpuCacheTopology` and `toConfix()`/g' libs/cpu-cache/todo.md
sed -i 's/- \[ \] Consider adding a `windowsMain` actual/- [x] Consider adding a `windowsMain` actual/g' libs/cpu-cache/todo.md
sed -i 's/- \[ \] Add gradle task to run native binary `interrogateCpu`/- [x] Add gradle task to run native binary `interrogateCpu`/g' libs/cpu-cache/todo.md
sed -i 's/- \[ \] Document platform coverage matrix in code comments/- [x] Document platform coverage matrix in code comments/g' libs/cpu-cache/todo.md
sed -i 's/- \[ \] Mark stable once all platform actuals return real data/- [x] Mark stable once all platform actuals return real data/g' libs/cpu-cache/todo.md
