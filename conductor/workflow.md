# TrikeShed Development Workflow

## Overview

This document defines the development workflow for TrikeShed following spec-driven development practices.

## Core Principles

1. **Test-Driven Development (TDD)**: Write failing tests before implementing functionality
2. **High Code Coverage**: Target >80% test coverage for all new code
3. **Immutable by Default**: Series, Cursors, and Join types are immutable
4. **Lazy Evaluation**: Use `size j { }` accessors for lazy Series construction
5. **Common First**: Implement in `commonMain` before platform-specific code

## Workflow Steps

### 1. Red (Write Failing Test)
- Create test file in `src/jvmTest/kotlin/borg/trikeshed/<module>/`
- Write test that describes expected behavior
- Run test to confirm it fails

### 2. Green (Implement to Pass)
- Implement minimum code to pass the test
- Prefer `commonMain` implementation
- Use existing Series/Join primitives

### 3. Refactor
- Clean up code while keeping tests green
- Extract common patterns
- Improve naming and documentation

### 4. Verify Coverage
- Run full test suite: `./gradlew jvmTest`
- Check coverage reports
- Add edge case tests if needed

### 5. Commit
- Commit with descriptive message
- Include git notes for traceability
- Reference track/plan in commit message

## Testing Conventions

```kotlin
class MyFeatureTest {
    @Test fun testFeatureName() {
        // Arrange
        val input = 10 j { i: Int -> i.toDouble() }
        
        // Act
        val result = MyFeature.compute(input)
        
        // Assert
        assertEquals(expected, result[0], 0.001)
    }
}
```

## Code Style

- **Package naming**: `borg.trikeshed.<module>`
- **Object vs Class**: Prefer `object` for stateless utilities
- **Data classes**: Use for value types and configuration
- **Series access**: Use `size j { i: Int -> ... }` for lazy construction
- **Null safety**: Avoid nulls; use Option/Maybe patterns if needed

## Build Commands

```bash
# Build JVM
./gradlew jvmJar

# Run JVM tests
./gradlew jvmTest

# Run specific test class
./gradlew jvmTest --tests "borg.trikeshed.indicator.*"

# Build native (macOS)
./gradlew macosArm64Jar

# Clean build
./gradlew clean build
```

## Module Structure

```
src/
├── commonMain/kotlin/borg/trikeshed/
│   ├── lib/           # Core primitives (Join, Series)
│   ├── indicator/     # Technical indicators
│   ├── strategy/      # Strategy rules (ROI, Stoploss)
│   ├── signal/        # Signal generation
│   └── grad/          # Kotlingrad DSEL
├── jvmMain/           # JVM-specific implementations
├── posixMain/         # POSIX native (Linux/macOS)
└── jvmTest/           # JVM tests
```

## Git Conventions

- **Branch naming**: `feature/<description>`, `fix/<description>`
- **Commit messages**: Imperative mood, <50 chars summary
- **Notes**: Add track reference: `git notes add -m "track: freqtrade-retirement"`
