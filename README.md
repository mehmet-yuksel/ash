# Ash Programming Language

I liked how simple and non-intrusive it was to use [libgc](https://github.com/bdwgc/bdwgc). I wanted to have type-safety too. I implemented what Graydon Hoare, the creator of Rust, mentioned in his [blog post](https://graydon2.dreamwidth.org/307291.html). It's called second-class references.

The language is: use ownership rules by default but use GC for any specific object you want. So it is somewhere between Rust and C.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Usage: Compiling](#usage-compiling)
- [Building the Compiler](#building-the-compiler)
- [Dependencies](#dependencies)
- [Language Reference](#language-reference)
  - [Ownership and Memory Management](#ownership-and-memory-management)
  - [Mutability](#mutability)
  - [Borrowing and Function Parameters](#borrowing-and-function-parameters)

### Example: Owned vs. Managed Types

```rust
struct Point {
  x: int,
  y: int
}

struct SharedConfig {
  api_key: int
}

fn main() -> unit {
  let p1 = Point { x: 10, y: 20 };
  let p2 = p1; // Ownership is moved from p1 to p2.
  println!("{}", p1.x); // ERROR: p1 was moved.
  println!("{}", p2.x);   // OK

  let mut config1: managed SharedConfig = managed SharedConfig { api_key: 123 };
  let mut config2 = config1; // The handle is copied. Both point to the same data.

  config2.api_key = 999;
  println!("{}", config1.api_key); // Prints 999
}
```

## Prerequisites

To use the compiler, you need the following tools:

1.  **Mill:** For building the compiler. See the [official installation guide](https://mill-build.org/mill/cli/installation-ide.html).
2.  **GCC:** Version 14.3.0 or compatible.
3.  **Java Development Kit (JDK):** Required to run Mill and the Scala compiler.

## Usage: Compiling

### Write A Program

Create a file named `hello.ash`:

```rust
fn main() -> unit {
  println!("Hello from Ash!");
}
```

### Compile to C++

Pass the source file as an argument.

```bash
mill ash.run hello.ash
```

This will create `hello.cpp` in the same directory.

```
Generated hello.cpp
```

### Compile It to an Executable

Use GCC to compile the generated C++ file. The `-std=c++23` flag is required.

```bash
g++ -std=c++23 hello.cpp gc.cpp -o hello
```

This'll create an executable `hello.exe` (or `hello` on Linux/macOS).

### Run It

```bash
./hello.exe
```

You should see the following output:

```
Hello from Ash!
```

## Building the Compiler

If you want to work on the compiler, you can use these commands.

- `mill ash.compile` for compiling the compiler.
- `mill ash.run`
- `mill ash.test`
- `mill clean` clean all build artifacts.

## Language Reference

### Ownership and Memory Management

Ash has three kinds of types that determine how memory and resources are managed.

#### Owned Types (`struct`)

This is the default behavior. Owned types live on the stack and have a single owner. When the owner goes out of scope, the value is destroyed.

When an owned type is assigned to a new variable or passed to a function, its ownership is moved. The original variable can't be used anymore.

```rust
struct Point {
  x: int,
  y: int
}

fn main() -> unit {
  let p1 = Point { x: 10, y: 20 };

  // Ownership is moved from p1 to p2.
  let p2 = p1;

  println!("{}", p1.x); // ERROR: use of moved value 'p1'

  println!("{}", p2.x);
}
```

#### Managed Types (`managed`)

If you want a garbage-collected type that can be shared, use `moved` keyword in the variable declaration.

```rust
struct SharedConfig {
  api_key: int,
  retries: int
}

fn main() -> unit {
  // config1 is a handle to a SharedConfig object on the heap.
  let mut config1: managed SharedConfig = managed SharedConfig { api_key: 123, retries: 3 };

  // Handles are copied. Both config1 and config2 now point to the same object.
  let mut config2 = config1;

  // Modifying the data through one handle is visible through all other handles.
  config2.retries = 5;

  println!("{}", config1.retries); // Prints 5
}
```

When an object is allocated as `managed`, it and everything in it are placed on the heap.

```rust
struct Bar { value: int }
struct Foo { bar: Bar }

fn main() -> unit {
  // The nested Bar object is also allocated on the heap.
  let mut foo: managed Foo = managed Foo { bar: Bar { value: 42 } };

  // Accessing foo.bar doesn't give a linear `Bar`.
  let mut bar_handle: managed Bar = foo.bar;

  // You can share and modify the nested Bar object independently.
  let mut another_bar_handle = bar_handle;
  another_bar_handle.value = 99;

  println!("{}", foo.bar.value); // Prints 99

  // This is an error because you can't move out of an object and the type of `foo.bar` isn't `Bar`, it's `managed Bar`.
  let b: Bar = foo.bar; // ERROR: cannot move out of managed context
}
```

#### Resource Types (`resource`)

If you want to prevent a type from being garbage allocated, you can use `resource` keyword.

```rust
resource File {
  descriptor: int

  cleanup {
    println!("Closing the file with descriptor: {}", descriptor);
  }
}

fn main() -> unit {
  let f = File { descriptor: 5 };

  // This raises a compile error.
  let managed_f: managed File = managed File { descriptor: 6 };
}
```

### Mutability

Immutability is the default.

```rust
fn takes_ownership_and_mutates(pt: mut Point) -> unit {
  pt.x = 100;
}

fn main() -> unit {
  let mut p = Point { x: 10, y: 20 };
  p.x = 15;

  takes_ownership_and_mutates(p); // Ownership is moved.
}
```

Here, you can't modify `pt`:

```rust
fn takes_ownership(pt: Point) -> unit {
  pt.x = 100; // ERROR: cannot assign to immutable variable 'pt'
}
```

### Borrowing and Function Parameters

To access data without taking ownership, you can declare a parameter to be immutable or mutable reference.

#### Immutable Borrows (`ref`)

A `ref` parameter provides read-only access to a value.

```rust
fn inspect_point(pt: ref Point) -> unit {
  println!("{}", pt.x);
  pt.x = 100; // ERROR
}

fn main() -> unit {
  let p = Point { x: 10, y: 20 };
  inspect_point(p); // Pass an immutable borrow of p.
  println!("{}", p.x);   // OK, p is still owned and valid.
}
```

#### Mutable Borrows (`inout`)

An `inout` parameter provides read-write access to a value.
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
