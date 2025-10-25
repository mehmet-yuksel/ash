struct Point {
  x: int,
  y: int
}

fn foo(p: Point) -> unit { }

fn bar(p: managed Point) -> unit { }

fn main() -> unit {
  let p1 = Point { x: 1, y: 2 };
  foo(p1);
  println!("{}", p1.x); // compile error: use of moved value

  let p2 = managed Point { x: 1, y: 2 };
  bar(p2);
  println!("{}", p2.x); // this is fine
}
