# Double Dispatch Library

A Kotlin Multiplatform library providing utilities and patterns for implementing double dispatch mechanisms.

## Overview

Double dispatch is a mechanism that dispatches a function call to different concrete implementations depending on the runtime types of two objects involved in the call. This library provides a clean, type-safe way to implement the visitor pattern and other double dispatch scenarios in Kotlin.

## Features

- **Type-safe visitor pattern** implementation
- **Multiplatform support** - works on JVM, JS, WasmJS, and native platforms
- **Extensible architecture** for custom double dispatch scenarios
- **Clean separation** of visitor and visitee concerns

## Usage

### Basic Visitor Pattern

```kotlin
// Define your visitor
interface MyVisitor : DoubleDispatchVisitor<String> {
    override fun visit(obj: Any): String {
        return when (obj) {
            is MyType1 -> "Type1"
            is MyType2 -> "Type2"
            else -> "Unknown"
        }
    }
}

// Make your objects dispatchable
class MyType1 : DoubleDispatchable {
    override fun <T> accept(visitor: DoubleDispatchVisitor<T>): T {
        return visitor.visit(this)
    }
}

// Use the visitor
val obj = MyType1()
val visitor = MyVisitor()
val result = obj.accept(visitor) // Returns "Type1"
```

### Shape Example

The library includes a comprehensive example using geometric shapes:

```kotlin
val shapes: List<Shape> = listOf(
    Circle(5.0),
    Rectangle(4.0, 6.0)
)

val areaVisitor = AreaVisitor()
val areas = shapes.map { it.accept(areaVisitor) }
```

## Building

```bash
./gradlew :libs:doubledispatch:build
```

## Testing

```bash
./gradlew :libs:doubledispatch:test
```

## Platform Support

- **JVM** - Full support with Java 21 toolchain
- **JavaScript** - Node.js runtime support
- **WasmJS** - WebAssembly support
- **Native** - macOS ARM64 support

## Dependencies

- Kotlin 2.4.0-Beta2
- Kotlin Multiplatform
- Root TrikeShed project

## License

Part of the TrikeShed project.
