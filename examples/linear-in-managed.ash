struct Bar {
  x: int
}

struct Foo {
  bar: Bar
}

fn move_bar(bar: Bar) {}

fn main() -> unit {
  let bar = Bar { x: 1 };
  let foo = managed Foo { bar: bar };
  move_bar(foo.bar);
  print_int(foo.bar.x); // this should be a compile error because bar is moved but now foo behaves like a linear type too.
  // `foo` behaves just like a linear type hear anyway so it's pointless to allow linear types to be contained in a managed one.

}