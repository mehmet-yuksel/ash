fn close(fd: int) {
    // A native function
}

resource File {
  fd: int

  cleanup {
    close(fd);
  }
}

fn main() -> unit {
  let f = managed File {}; // this is a compile error: cannot make a resource garbage collected.
}
