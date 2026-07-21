# Summary of nexus TODO

This document summarizes the main tasks planned for the nexus project.

## Architecture Rebuild
The project plans a significant architecture rebuild, which includes:
- Deleting the `src/commonMain/BROKEN/` directory.
- Removing old files like `NexusTypes_OLD.kt` and `DefaultNexusAgent.kt`.
- Eliminating overly-abstract agent design components.

## New Architecture Implementation
Key tasks for implementing the new architecture involve:
- Creating a main entry point with argument parsing (following the k2script pattern).
- Adding `NexusConfigBuilder` for settings management.
- Developing an `ActionExecutor` for task handling.
- Integrating `LiteLLMClient` as the core AI provider.
- Implementing basic environment scanning capabilities.

## Core Agent Features
The core features for the agent include:
- A basic task execution engine.
- Discovery of environment capabilities.
- Analysis of project structure.
- A framework for tool orchestration.

## IntelliJ PSI Integration
Integration with IntelliJ's Program Structure Interface (PSI) will cover:
- Setting up a PSI analysis module.
- Implementing semantic code understanding.
- Adding capabilities for type-aware refactoring.
- Creating an LSP server for universal editor support.

## Testing and Validation
Comprehensive testing will be performed, including:
- Integration tests for the new architecture.
- Tests for the LLM provider integration.
- Validation of environment scanning accuracy.
- Performance testing, especially for large projects.
```
