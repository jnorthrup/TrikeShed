# Product Guide

## Product Name
trikeshed

## Product Summary
trikeshed is the Kotlin core for immutable series/cursor algebra and expression-based compute paths used by adjacent trading systems.

## Current Conductor Focus
Retire `freqtrade` and extract beneficial feature/strategy logic into `moneyfan` (full-stack/HRM) or `curly-succotash` (I/O) while keeping core Series/Cursor algebra in TrikeShed.

## Scope Clarifications
- `hrm` logic is located in the sibling repository `../moneyfan`.
- Input/output concerns are handled by `../curly-succotash`.
- TrikeShed is the source of truth for Series/Cursor/Grammar; Leaf nodes live in consuming apps.
- `freqtrade` is being retired; beneficial logic (indicators, strategy hooks) is being extracted.
