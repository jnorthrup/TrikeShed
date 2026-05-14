# TrikeShed RLM Gradle hooks

Manual-only tasks for library policing.

Per-lib tasks
- trikeshedModuleBrief
- trikeshedLint
- trikeshedDelegatePacket
- trikeshedRefineRules

All-libs tasks
- trikeshedBriefLibs
- trikeshedLintLibs
- trikeshedDelegatePacketsLibs
- trikeshedRefineLibRules

Nothing is wired into build/check/test/assemble.

Optional Gradle properties
- -Ptrikeshed.rlm.python=/path/to/python3
- -Ptrikeshed.rlm.hermesCommand=/Users/jim/.local/bin/hermes

Per-module rule files live under gradle/trikeshed-rlm/libs/ with names derived from project paths:
- :libs:common -> gradle/trikeshed-rlm/libs/libs__common.json
- :libs:couch:viewserver -> gradle/trikeshed-rlm/libs/libs__couch__viewserver.json

Refine writes proposals to build/trikeshed-rlm/refinement-proposal.json by default. Review before copying into gradle/trikeshed-rlm/libs/.
