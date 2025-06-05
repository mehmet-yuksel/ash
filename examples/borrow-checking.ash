// Struct definition
struct Point {
  x: int,
  y: int
}

// Function definition
fn main() -> unit {
  let p1: Point = Point { x: 10, y: 20 };
  let p2: Point = p1; // p1 is moved to p2

  // Assuming a built-in print_int that takes int by value (copy)
  print_int(p2.x); // OK

  // print_int(p1.x); // ERROR: p1 used after move
}

fn pass_by_move(pt: Point) -> unit {
  print_int(pt.x);
}

fn use_pass_by_move() -> unit {
  let p3 = Point { x: 30, y: 30 };
  pass_by_move(p3); // p3 is moved into pass_by_move
  // print_int(p3.x); // ERROR: p3 used after move
}

fn pass_by_ref(pt: ref Point) -> unit {
  print_int(pt.x); // OK to read
  // pt.x = 100; // ERROR: cannot modify through immutable ref
}

fn pass_by_inout(pt: inout Point) -> unit {
  print_int(pt.x);
  pt.x = pt.x + 1; // OK to modify
}

fn use_borrows() -> unit {
  let p4 = Point { x: 40, y: 40 };
  pass_by_ref(p4);    // Pass immutable reference
  print_int(p4.x);   // p4 still owned and usable

  pass_by_inout(p4); // Pass mutable reference
  print_int(p4.x);   // p4 still owned, value might have changed
}