resource File {}

fn main() -> unit {
  let f = managed File {}; // this is a compile error: cannot make a resource garbage collected.
}