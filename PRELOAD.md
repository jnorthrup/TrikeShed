# Typealias Cheatsheet for Trikeshed Project

## Core Tenets

1. Clarity over brevity
2. Composition over inheritance
3. Immutability by default
4. Lazy evaluation when possible
5. Leverage Series and Join as fundamental building blocks

## Rules

1. Always include full signatures in typealiases
2. Prefer typealiases over direct type definitions for complex types
3. Use meaningful names that describe the concept, not just the underlying type
4. Avoid nested typealiases when possible; flatten them if it improves readability

## Priorities

1. Type safety and compile-time checks
2. Code readability and maintainability
3. Performance optimization through lazy evaluation and immutable structures
4. Flexibility for extension and composition

## Subsumptions

1. Series subsumes List and Array functionalities
2. Join subsumes Pair and Triple
3. Immutable data structures subsume mutable ones (provide mutable interfaces when necessary)

## Tradeoffs

1. Verbosity vs. Inference: Prefer explicit types over relying on Kotlin's type inference
2. Abstraction vs. Concreteness: Balance between high-level concepts and low-level implementations
3. Generality vs. Specificity: Aim for reusable components without sacrificing domain-specific optimizations

## Key Typealiases to Remember

```kotlin
typealias Series<T> = Join<Int, (Int) -> T>
typealias Twin<T> = Join<T, T>
typealias Bucket<T> = Series<T>
typealias Version = Int
typealias HashBody<T> = Join<Version, Series<Bucket<T>>>
```

## Design Patterns

1. Use Join for pairing concepts
2. Use Series for sequences and collections
3. Implement immutable cores with mutable wrappers when needed
4. Utilize lazy evaluation through lambda expressions in Join and Series
5. Do not introduce List-backed storage in core algebra when a Series surface can carry the same shape
6. Keep pointcutting and Rete fabric at the integration boundary where production behavior exists; do not inject them into foundation galleries or signal surfaces gratuitously
7. Native target support may be host-only; disable unsupported x86 and linux-aarch fallbacks unless the user explicitly needs that target
8. JVM-bearing modules target toolchain 25 for the SDKMAN GraalCE baseline
9. TODO-only future contracts should still end in explicit CCEK choreography surfaces for command-line and generated API entry points: data -> LCNC cursor facets -> user-signalling events

## Refactoring Guidelines

1. Look for opportunities to replace standard library collections with Series
2. Convert complex generic types to typealiases for improved readability
3. Refactor mutable structures to immutable ones with versioning (like HashBody)
4. Use extension functions on typealiases to add functionality without modifying core structures

 
