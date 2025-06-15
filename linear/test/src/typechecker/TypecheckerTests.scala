package linear.typechecker

import utest._
import linear.parser

object TypecheckerTests extends utest.TestSuite {
  def tests = Tests {
    test("moves") {
      test("move allows assignment") {
        val code = """
        struct Point {
          x: int,
          y: int
        }

        fn translate(pt: inout Point) -> unit {
          pt.x = pt.x;
        }

        fn main() -> unit {
          let mut p = Point { x: 10, y: 20 };
          translate(p);
          let p2 = p; // p is moved
        }
      """
        val languageParser = new parser.LanguageParser(code)
        val programAst = languageParser.parseProgram()
        val typechecker = new Typechecker(programAst)
        val typedProgram = typechecker.check()
        assert(true)
      }
      test("move doesn't allow reassign") {
        val e = intercept[TypeError] {
          val code = """
        struct Point {
          x: int,
          y: int
        }

        fn main() -> unit {
          let mut p = Point { x: 10, y: 20 };
          let p2 = p; // p is moved
          p2.x = 30; // Error, cannot assign to moved value
        }
      """
          val languageParser = new parser.LanguageParser(code)
          val programAst = languageParser.parseProgram()
          val typechecker = new Typechecker(programAst)
          val typedProgram = typechecker.check()
        }
        assert(
          e.getMessage.contains("Cannot assign")
        )
      }

      test("cannot use moved value") {
        val e = intercept[TypeError] {
          val code = """
          struct Point {
            x: int,
            y: int
          }
          fn print(pt: Point) -> unit {
            // A native function we assume exists
          }
          fn main() -> unit {
            let mut p = Point { x: 10, y: 20 };
            let p2 = p; // p is moved
            print(p); // Error, cannot use moved value
          }
      """
          val languageParser = new parser.LanguageParser(code)
          val programAst = languageParser.parseProgram()
          val typechecker = new Typechecker(programAst)
          val typedProgram = typechecker.check()
        }
        assert(
          e.getMessage.contains("Use of moved value")
        )
      }
    }
    test("inout") {
      test("inout allows mutation") {
        val code = """
        struct Point {
          x: int,
          y: int
        }

        fn mutate(pt: inout Point) -> unit {
          pt.x = 42;
        }

        fn main() -> unit {
          let mut p = Point { x: 10, y: 20 };
          mutate(p);
        }
      """
        val languageParser = new parser.LanguageParser(code)
        val programAst = languageParser.parseProgram()
        val typechecker = new Typechecker(programAst)
        val typedProgram = typechecker.check()
        assert(true)
      }
      test("inout doesn't moves") {
        val e = intercept[TypeError] {
          val code = """
        struct Point {
          x: int,
          y: int
        }

        fn take_inout(pt: inout Point) -> unit {
          consume(pt);
        }

        fn consume(pt: Point) -> unit {
          // Consumes the Point
        }

        fn main() -> unit {
          let mut p = Point { x: 10, y: 20 };
          take_inout(p);
        }
      """
          val languageParser = new parser.LanguageParser(code)
          val programAst = languageParser.parseProgram()
          val typechecker = new Typechecker(programAst)
          val typedProgram = typechecker.check()
        }
        assert(
          e.getMessage.contains("Cannot move")
        )
      }
    }
    test("ref") {
      test("ref allows read") {
        val code = """
        struct Point {
          x: int,
          y: int
        }

        fn print_point(p: ref Point) -> unit {
          // A native function we assume exists
        }

        fn main() -> unit {
          let mut p = Point { x: 10, y: 20 };
          print_point(p);
        }
      """
        val languageParser = new parser.LanguageParser(code)
        val programAst = languageParser.parseProgram()
        val typechecker = new Typechecker(programAst)
        val typedProgram = typechecker.check()
        assert(true)
      }
      test("ref doesn't allow mutation") {
        val e = intercept[TypeError] {
          val code = """
        struct Point {
          x: int,
          y: int
        }

        fn take_ref(pt: ref Point) -> unit {
          pt.x = 2;
        }

        fn main() -> unit {
          let mut p = Point { x: 10, y: 20 };
          take_ref(p);
        }
      """
          val languageParser = new parser.LanguageParser(code)
          val programAst = languageParser.parseProgram()
          val typechecker = new Typechecker(programAst)
          val typedProgram = typechecker.check()
        }
        assert(
          e.getMessage.contains("Cannot assign")
        )
      }
    }
    test("managed types") {
      test("nested managed struct initialization") {
        val code = """
        struct Bar { val: int }
        struct Foo { bar: Bar }
        fn main() -> unit {
          let foo = managed Foo { bar: Bar { val: 42 } };
          let b: managed Bar = foo.bar;
        }
        """
        val languageParser = new parser.LanguageParser(code)
        val programAst = languageParser.parseProgram()
        val typechecker = new Typechecker(programAst)
        val typedProgram = typechecker.check()
        assert(true)
      }

      test("field access on managed struct preserves managed property") {
        val code = """
        struct Bar { val: int }
        struct Foo { bar: Bar }
        fn main() -> unit {
          let foo = managed Foo { bar: Bar { val: 42 } };
          let b = foo.bar;
          let c: Bar = b;
        }
        """
        val e = intercept[TypeError] {
          val languageParser = new parser.LanguageParser(code)
          val programAst = languageParser.parseProgram()
          val typechecker = new Typechecker(programAst)
          typechecker.check()
        }
        assert(
          e.getMessage == "Type mismatch for 'c'. Expected Bar but got managed Bar."
        )
      }

      test("mutable field access on managed struct") {
        val code = """
        struct Bar { val: int }
        struct Foo { bar: Bar }
        fn main() -> unit {
          let mut foo = managed Foo { bar: Bar { val: 42 } };
          foo.bar.val = 100;
        }
        """
        val languageParser = new parser.LanguageParser(code)
        val programAst = languageParser.parseProgram()
        val typechecker = new Typechecker(programAst)
        val typedProgram = typechecker.check()
        assert(true)
      }

      test("cannot assign linear struct to managed field inside literal") {
        val code = """
        struct Bar { val: int }
        struct Foo { bar: Bar }
        fn main() -> unit {
          let linear_bar = Bar { val: 1 };
          let foo = managed Foo { bar: linear_bar };
        }
        """
        val e = intercept[TypeError] {
          val languageParser = new parser.LanguageParser(code)
          val programAst = languageParser.parseProgram()
          val typechecker = new Typechecker(programAst)
          typechecker.check()
        }
        assert(
          e.getMessage == "Type mismatch for field 'bar' in 'Foo' initialization. Expected managed Bar but got Bar."
        )
      }
    }
  }
}
