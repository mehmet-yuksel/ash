# Ash Programming Language

Ash is a systems programming language designed to bridge the gap between high-performance systems languages and high-convenience application languages. This repository contains the official compiler for Ash, written in Scala 3.

The compiler takes an Ash source file (`.ash`) and transpiles it into C++ code, which can then be compiled into a native executable.

## Table of Contents

- [Language Philosophy](#language-philosophy)
- [Core Features](#core-features)
- [Prerequisites](#prerequisites)
- [Usage: Compiling Your First Ash Program](#usage-compiling-your-first-ash-program)
- [Building the Compiler](#building-the-compiler)
- [Dependencies](#dependencies)
- [Language Reference](#language-reference)
  - [Ownership and Memory Management](#ownership-and-memory-management)
  - [Mutability](#mutability)
  - [Borrowing and Function Parameters](#borrowing-and-function-parameters)

## Language Philosophy

Modern programming languages often force a difficult choice between performance and convenience.

- **Systems languages** like C++ and Rust offer fine-grained control over memory, enabling maximum performance and deterministic resource management. However, this comes at the cost of a steep learning curve, manual memory management, or wrestling with complex concepts like the borrow checker.

- **Application languages** like Java, C#, and Python offer garbage collection (GC), which simplifies development by automatically managing memory. This convenience comes at the cost of performance overhead, unpredictable GC pauses, and non-deterministic cleanup of resources like file handles or network sockets.

**Ash** is designed to bridge this gap. It is a systems programming language built on a simple philosophy: **control by default, convenience by choice.**

Ash provides the performance and predictability of linear types (ownership and moves) as the default behavior, while allowing any data structure to be opted into garbage collection for scenarios where lifetimes are complex and shared ownership is needed.

This hybrid approach allows developers to write high-performance, resource-critical code on the hot path, while seamlessly switching to a more convenient, garbage-collected model for complex, long-lived, or widely shared data.

## Core Features

- **Linear Types by Default:** All user-defined types follow move semantics, ensuring that there is only one owner for any given piece of data. This eliminates entire classes of bugs like use-after-free and double-free, and provides deterministic destruction of objects.
- **Opt-in Garbage Collection:** Any data structure can be allocated on a managed heap, where its lifetime will be handled by a garbage collector. This provides shared, mutable access to data without the complexity of manual lifetime management.
- **Resource Safety:** A special `resource` type guarantees deterministic cleanup and cannot be placed on the GC heap, preventing resource leaks.
- **Simple Borrowing:** Ash uses temporary, non-owning references (`ref` and `inout`) for function parameters, enabling data to be passed without transferring ownership. This avoids the complexity of a full-fledged borrow checker.
- **Explicit Mutability:** Immutability is the default. Both variables and owned parameters must be explicitly marked as `mut` to be modified, increasing code safety and clarity.

### Example: Linear vs. Managed Types

```rust
// A standard linear type with move semantics.
struct Point {
  x: int,
  y: int
}

// A type that can be shared and managed by the GC.
struct SharedConfig {
  api_key: int
}

fn main() -> unit {
  // --- Linear Type (on the stack) ---
  let p1 = Point { x: 10, y: 20 };
  let p2 = p1; // Ownership is MOVED from p1 to p2.
  // println!("{}", p1.x); // COMPILE ERROR: p1 was moved.
  println!("{}", p2.x);   // OK

  // --- Managed Type (on the heap) ---
  let mut config1: managed SharedConfig = managed SharedConfig { api_key: 123 };
  let mut config2 = config1; // The handle is COPIED. Both point to the same data.

  // Modifications are visible through all handles.
  config2.api_key = 999;
  println!("{}", config1.api_key); // Prints 999
}
```

## Prerequisites

Before you can use the Ash compiler, you need to have the following tools installed:

1.  **Mill:** A Scala build tool. See the [official installation guide](https://mill-build.org/mill/cli/installation-ide.html).
2.  **Clang:** A C++ compiler, version **20.1.5** or compatible.
3.  **Java Development Kit (JDK):** Required to run Mill and the Scala compiler.

## Usage: Compiling Your First Ash Program

Follow these steps to write, compile, and run an Ash program.

### Step 1: Write an Ash Program

Create a file named `hello.ash` with the following content:

```rust
// hello.ash
fn main() -> unit {
  println!("Hello from Ash!");
}
```

### Step 2: Compile Ash to C++

Run the Ash compiler using Mill, passing your source file as an argument. This will parse your Ash code, type-check it, and generate an equivalent C++ file.

```bash
mill ash.run hello.ash
```

This command will create a new file, `hello.cpp`, in the same directory.

```
Generated hello.cpp
```

### Step 3: Compile C++ to a Native Executable

Use Clang to compile the generated C++ file. The `-std=c++23` flag is required.

```bash
clang hello.cpp -o hello.exe -std=c++23
```

This creates a native executable file named `hello.exe` (or `hello` on Linux/macOS).

### Step 4: Run Your Program

Execute the compiled program.

```bash
./hello.exe
```

You should see the following output:

```
Hello from Ash!
```

## Building the Compiler

If you want to work on the Ash compiler itself, you can use these Mill commands.

- `mill ash.compile` - Compile the compiler source code.
- `mill ash.run` - Run the compiler (you must pass a source file, e.g., `mill ash.run examples/my_program.ash`).
- `mill ash.test` - Run the compiler's test suite.
- `mill clean` - Clean all build artifacts.

## Dependencies

- **Compiler Language:** Scala 3.3.1
- **Build Tool:** Mill
- **C++ Target:** C++23 standard
- **C++ Compiler:** Clang 20.1.5 (recommended)

## Language Reference

This section provides a more detailed reference for the language features.

### Ownership and Memory Management

Ash has three fundamental kinds of types that determine how memory and resources are managed.

#### Linear Types (`struct`)

This is the default behavior in Ash. Linear types live on the stack and have a single owner. When the owner goes out of scope, the value is destroyed.

When a linear type is assigned to a new variable or passed to a function, its ownership is **moved**. The original variable can no longer be used.

```rust
struct Point {
  x: int,
  y: int
}

fn main() -> unit {
  // p1 is a linear value on the stack. It owns the data.
  let p1 = Point { x: 10, y: 20 };

  // Ownership of the data is moved from p1 to p2.
  let p2 = p1;

  // This line would cause a compile error because p1 no longer owns the data.
  // println!("{}", p1.x); // ERROR: use of moved value 'p1'

  // p2 is the current owner.
  println!("{}", p2.x); // OK
}
```

#### Managed Types (`managed`)

For data with complex lifetimes or that needs to be shared, you can choose to allocate it on the garbage-collected heap using the `managed` keyword at the allocation site.

A variable of a `managed` type is a lightweight **handle** (or reference) to the data on the heap. Handles are cheap to copy, and multiple handles can point to the same data. The data will be kept alive as long as at least one handle to it exists.

```rust
struct SharedConfig {
  api_key: int,
  retries: int
}

fn main() -> unit {
  // config1 is a handle to a SharedConfig object on the GC heap.
  let mut config1: managed SharedConfig = managed SharedConfig { api_key: 123, retries: 3 };

  // Handles are copied, not moved. Both config1 and config2 now point to the same object.
  let mut config2 = config1;

  // Modifying the data through one handle is visible through all other handles.
  config2.retries = 5;

  println!("{}", config1.retries); // Prints 5
}
```

**The Managed Boundary Rule:** When an object is allocated as `managed`, it and everything it contains are placed on the GC heap. This has two important consequences:

1.  **Propagation:** The `managed` property propagates downwards. Accessing a field of a `managed` object gives you a `managed` handle to that field. Similarly, any nested structs initialized within a `managed` context are also automatically allocated on the heap.
2.  **Immovability:** You cannot move any value out from within this boundary. This ensures the integrity of the shared data structure.

```rust
struct Bar { value: int }
struct Foo { bar: Bar }

fn main() -> unit {
  // foo is a handle to a managed Foo object.
  // The nested Bar object is also allocated on the heap.
  let mut foo: managed Foo = managed Foo { bar: Bar { value: 42 } };

  // Accessing foo.bar doesn't give a linear `Bar`.
  // Instead, it gives a `managed Bar` handle.
  let mut bar_handle: managed Bar = foo.bar;

  // We can now share and modify the nested Bar object independently.
  let mut another_bar_handle = bar_handle;
  another_bar_handle.value = 99;

  println!("{}", foo.bar.value); // Prints 99

  // This would be a compile error because you cannot move data out of the managed boundary.
  // let b: Bar = foo.bar; // ERROR: cannot move out of managed context
}
```

#### Resource Types (`resource`)

Some types, like file handles, network sockets, or mutex locks, require immediate and predictable cleanup. These should be defined as `resource` types.

A `resource` behaves like a linear `struct` (it has a single owner and is moved), but with one critical guarantee: **a `resource` can never be allocated on the garbage-collected heap.** This prevents its lifetime from being indeterminately extended, ensuring that cleanup happens exactly when it goes out of scope.

```rust
resource File {
  descriptor: int

  cleanup {
    println!("Closing the file with descriptor: {}", descriptor);
  }
}

fn main() -> unit {
  // A resource is allocated linearly.
  let f = File { descriptor: 5 };

  // This would be a compile error, ensuring the File is not kept alive by the GC.
  // let managed_f: managed File = managed File { descriptor: 6 }; // ERROR
}
```

### Mutability

In Ash, immutability is the default. To mutate a value, its binding must be explicitly marked with `mut`. This applies to local variables and function parameters that take ownership.

```rust
fn takes_ownership_and_mutates(pt: mut Point) -> unit {
  pt.x = 100; // OK, because the 'pt' binding is mutable.
}

fn main() -> unit {
  let mut p = Point { x: 10, y: 20 };
  p.x = 15; // OK, because the 'p' binding is mutable.

  takes_ownership_and_mutates(p); // Ownership is moved.
}
```

A function that takes an owned parameter without `mut` cannot modify it.

```rust
fn takes_ownership(pt: Point) -> unit {
  // This would be a compile error.
  // pt.x = 100; // ERROR: cannot assign to immutable binding 'pt'
}
```

### Borrowing and Function Parameters

To access data without taking ownership, Ash uses temporary borrows passed as function parameters. This avoids the need for a complex lifetime system.

#### Immutable Borrows (`ref`)

A `ref` parameter provides read-only access to a value. The caller retains ownership of the original data.

```rust
fn inspect_point(pt: ref Point) -> unit {
  println!("{}", pt.x);
  // pt.x = 100; // ERROR: cannot modify through an immutable reference
}

fn main() -> unit {
  let p = Point { x: 10, y: 20 };
  inspect_point(p); // Pass an immutable borrow of p.
  println!("{}", p.x);   // OK, p is still owned and valid.
}
```

#### Mutable Borrows (`inout`)

An `inout` parameter provides read-write access to a value. The caller must provide a mutable variable.

```rust
fn translate(pt: inout Point) -> unit {
  pt.x = pt.x + 1;
}

fn main() -> unit {
  let mut p = Point { x: 10, y: 20 };
  translate(p);      // Pass a mutable borrow of p.
  println!("{}", p.x);    // Prints 11
}
```
