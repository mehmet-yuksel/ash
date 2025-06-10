### Feature 1: Primitives and Basic Expressions

This is the foundation. Everything else builds on it.

- **Type Checker:**

  - Recognize literal types: `10` is `Type.Int`, `true` is `Type.Bool`.
  - Implement type checking for basic binary operations (`+`, `-`, `==`, `&&`, etc.). Ensure that `1 + true` is a type error.
  - Handle `let x = 10;`. The type of `x` is inferred as `Type.Int`. Store this in the symbol table.
  - The symbol for `x` is marked as a `Copy` type (or just handle primitives as a special case that don't get "moved").

- **Interpreter:**
  - Implement `Value.Int`, `Value.Bool`, `Value.Unit`.
  - Evaluate literals to their corresponding `Value` objects.
  - Implement the logic for binary operations (e.g., get two `Value.Int`s, add their contents, return a new `Value.Int`).
  - When interpreting `let x = 10;`, store the mapping `"x" -> Value.Int(10)` in the current stack frame.

---

### Feature 2: Linear Structs and Move Semantics

This is the core of your ownership system.

- **Type Checker:**

  - **Struct Definition:** When checking a `struct Point { ... }`, create a `Type.Struct` representation and store it in the global context.
  - **Instantiation:** For `Point { x: 1, y: 2 }`, verify that the fields and types match the struct's definition.
  - **Symbol Table:** When a variable `p1` gets a linear struct, add it to the symbol table with `state = VarState.Valid`.
  - **Move on Assignment:** For `let p2 = p1;`, check that `p1` is `Valid`. If so, set `p1`'s state to `VarState.Moved`. `p2` is now `Valid`.
  - **Move on Function Call:** When passing `p1` to a function that takes `p: Point` by value, do the same: set `p1`'s state to `VarState.Moved`.
  - **Use-After-Move Error:** If any expression tries to use `p1` while its state is `Moved`, throw a compile-time error.

- **Interpreter:**
  - Implement `Value.Struct` which holds a map of field names to other `Value`s.
  - **Instantiation:** Evaluate the field expressions and create a `Value.Struct`.
  - **Move Semantics:** When interpreting `let p2 = p1;`, get the `Value.Struct` from `p1`, assign it to `p2`, and **remove `p1` from the current stack frame's variable map**. This simulates the move at runtime. The type checker ensures this is safe.

---

### Feature 3: Borrowing (`ref` and `inout`)

This introduces temporary, non-owning references.

- **Type Checker:**

  - **Function Signatures:** Recognize `ref Point` and `inout Point` as special parameter types (borrows).
  - **Call Site:** When calling `foo(p1)`, if `foo` takes `ref Point`, check that `p1` is a valid `Point` (linear or managed). The state of `p1` does **not** change to `Moved`.
  - **Inside the Function:** The parameter `p` is treated as a borrow. Any attempt to move from `p` (e.g., `let other = p;`) is a type error.
  - **`inout` Specifics:** The variable passed to an `inout` parameter must be declared as mutable (`let mut p1 = ...`).

- **Interpreter:**
  - **The Challenge:** You can't use real pointers easily.
  - **Implementation Strategy:** Create `Location` and `Reference` values.
    - `enum Location { Stack(varName), Heap(id, fieldName) }`
    - `Value.Ref(Location)`
  - When calling `foo(p1)` where `p1` is on the stack, the value passed for the `ref` parameter is `Value.Ref(Location.Stack("p1"))`.
  - When the interpreter evaluates an expression involving this reference, it uses the `Location` to look up the _original_ value on the stack or heap to read from or write to.

---

### Feature 4: Managed Allocation (`managed`)

This introduces the GC heap and shared ownership.

- **Type Checker:**

  - **Type Representation:** Create `Type.Managed(underlying: Type.Struct)`. This is the type of a handle.
  - **Allocation:** For `managed Point { ... }`, check the struct literal as before, but the resulting type of the whole expression is `Type.Managed(Type.Struct("Point", ...))`.
  - **Handles are `Copy`:** When a `managed` handle is assigned or passed, it is copied, not moved. The original variable remains `Valid`.
  - **Polymorphic Borrows:** Verify that a `managed Point` can be passed to a function expecting `ref Point` or `inout Point`.
  - **Field Access Move-Safety:** Implement the crucial rule: if `p` is a `managed Point`, then an expression like `p.x` cannot be moved (if `x` were a linear type). The type checker must trace the origin of `p.x` back to the `managed` variable `p` and forbid the move.

- **Interpreter:**
  - Implement the `GcHeap` and `Value.ManagedHandle`.
  - **Allocation:** When interpreting `managed Point { ... }`, first create the `Value.Struct` for the `Point`, then call `heap.allocate(theStruct)`. The result of the expression is the returned `Value.ManagedHandle`.
  - **Field Access:** When evaluating `p.x`, evaluate `p` to get a `Value.ManagedHandle`. Use its ID to get the `Value.Struct` from the heap, then access the field `x`.
  - **GC Simulation (Simple):** For the interpreter, you don't need a full GC. The heap just grows. This is sufficient to test allocation and access logic.

---

### Feature 5: Resource Types

This adds the final layer of safety for deterministic cleanup.

- **Type Checker:**

  - **Type Representation:** Create `Type.Resource`. It behaves almost identically to a linear `Type.Struct`.
  - **The `resource` Rule:** Implement the check: when you see a `managed T` allocation, look up the base type `T`. If it's a `Type.Resource`, throw a compile-time error.
  - **The "No Linear in Managed" Rule:** Since you're not allowing linear fields in managed types, you must also forbid `resource` fields. The check is the same: when analyzing a `managed` allocation of `Foo`, iterate `Foo`'s fields. If any field is a `resource`, error.
  - _(Future Work)_ **Destructors:** The type checker would note that `resource` types have a `destroy()` method that must be called.

- **Interpreter:**
  - A `resource` at runtime is just a `Value.Struct`. There's no behavioral difference in the interpreter _unless_ you implement destructors.
  - _(Future Work)_ **Destructors:** When a linear `resource` variable goes out of scope, the interpreter would need to call its `destroy()` method. This requires tracking variable lifetimes, which your stack frames naturally do. When a frame is popped, you'd iterate its variables and call destructors for any resources.

By tackling the implementation in this order, each new feature builds upon a working, tested foundation. You start with simple values, add ownership, then add borrowing (which relaxes ownership temporarily), then add a second ownership model (GC), and finally add the most restrictive ownership model (`resource`).
