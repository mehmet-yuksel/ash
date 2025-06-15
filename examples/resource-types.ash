resource File {
  fd: int

  cleanup {
    // assume `close` is a native function.
    close(fd);
  }
}

fn main() -> unit {
  let f = managed File {}; // this is a compile error: cannot make a resource garbage collected.
}