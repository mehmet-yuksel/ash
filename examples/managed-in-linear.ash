struct Bar {}

struct Foo {
  bar: managed Bar
}

fn main() -> unit {
  let bar = managed Bar {};
  let foo = Foo { bar: bar };
  // This is fine. `bar` acts as a pointer to the actual object just like in other gc'd languages.
  // How do we track this reference even though `foo` isn't managed by the garbage collector? The same way stack frames aren't managed by the GC but it still works.
  // We essentially do roots scanning but this time we not only scan just the variables in a stack frame but their pointers too.
}