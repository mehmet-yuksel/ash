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
  pt.x = pt.x;
}

fn main() -> unit {
  println!("Starting program...");
  let p1: managed Point = managed Point { x: 10, y: 20 };
  let p2 = p1; // Handle is copied, not moved
  translate(p2);
  print_point(p1);
  print_point(p2);
  println!("Program finished!");
}
"""
    try {
      val languageParser = new LanguageParser(testCode)
      val programAst = languageParser.parseProgram()
      println("Successfully parsed managed types!")
      val typechecker = new Typechecker(programAst)
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
