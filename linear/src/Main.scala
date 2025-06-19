package linear

import parser.{
  ExpressionStatement,
  LetStatement,
  ParserError,
  LexerError,
  LanguageParser
}
import linear.typechecker.Typechecker
import linear.typechecker.TypeError
import linear.codegen.CppCodeGenerator
import java.nio.file.{Files, Paths}

object Main {
  def main(args: Array[String]): Unit = {
    val testCode = """
struct Point {
  x: int,
  y: int
}

resource File {
  fd: int

  cleanup {
    println!("Closing file descriptor: {}", fd);
  }
}


fn print_point(p: managed Point) -> unit {
  println!("Point(x: {}, y: {})", p.x, p.y);
}

fn translate(pt: mut managed Point) -> unit {
  pt.x = pt.x + 5;
  pt.y = pt.y - 3;
}

fn compare_points(p1: managed Point, p2: managed Point) -> unit {
  let same_x = p1.x == p2.x;
  let different_y = p1.y != p2.y;
  let x_greater = p1.x > p2.x;
  let y_less_equal = p1.y <= p2.y;
  
  println!("Same X: {}", same_x);
  println!("Different Y: {}", different_y);
  println!("X greater: {}", x_greater);
  println!("Y less or equal: {}", y_less_equal);
}

fn main() -> unit {
  println!("Starting program...");
  let p1: managed Point = managed Point { x: 10, y: 20 };
  let p2 = p1; // Handle is copied, not moved
  translate(p2);
  print_point(p1);
  print_point(p2);
  
  println!("Comparing points:");
  compare_points(p1, p2);
  
  let sum = 15 + 25;
  let diff = 30 - 10;
  let is_equal = sum == 40;
  let is_greater = diff >= 20;
  
  println!("Sum: {}, Diff: {}", sum, diff);
  println!("Sum equals 40: {}", is_equal);
  println!("Diff >= 20: {}", is_greater);
  
  println!("Program finished!");
}
"""
    try {
      val languageParser = new LanguageParser(testCode)
      val programAst = languageParser.parseProgram()
      println("Successfully parsed managed types!")
      val typechecker = new Typechecker(programAst, testCode)
      val typedProgram = typechecker.check()

      val codegen = new CppCodeGenerator(typedProgram)
      val cppCode = codegen.generate()
      val path = Paths.get("program.cpp")
      Files.write(path, cppCode.getBytes("UTF-8"))

    } catch {
      case e: LexerError =>
        println(s"Lexer error: ${e.getMessage}")
      case e: ParserError =>
        println(s"Parser error: ${e.getMessage}")
      case e: TypeError =>
        println(s"Type error: ${e.getMessage}")
    }
  }
}
