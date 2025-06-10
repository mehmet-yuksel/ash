package linear

import parser.{
  ExpressionStatement,
  LetStatement,
  ParserError,
  LexerError,
  LanguageParser
}

object Main {
  def main(args: Array[String]): Unit = {
    val testCode = """
struct Point {
  x: int,
  y: int
}

resource File {
  fd: int,
  path: int
}

fn foo(p: Point) -> unit { }

fn bar(p: managed Point) -> unit { }

fn main() -> unit {
  let p1 = Point { x: 1, y: 2 };
  foo(p1);
  print_int(p1.x);

  let p2 = managed Point { x: 1, y: 2 };
  bar(p2);
  print_int(p2.x);
}
"""
    println("\n--- Testing managed types parsing ---")
    try {
      val languageParser = new LanguageParser(testCode)
      val programAst = languageParser.parseProgram()
      println("Successfully parsed managed types!")

      // Show struct definitions
      programAst.structs.foreach { struct =>
        println(s"Struct: ${struct.name}")
        struct.fields.foreach { case (name, typ) =>
          println(s"  ${name}: ${typ}")
        }
      }

      // Show resource definitions
      programAst.resources.foreach { resource =>
        println(s"Resource: ${resource.name}")
        resource.fields.foreach { case (name, typ) =>
          println(s"  ${name}: ${typ}")
        }
      }

      // Show function definitions with parameter types
      programAst.functions.foreach { func =>
        println(s"Function: ${func.name}")
        func.params.foreach { param =>
          println(s"  ${param.name}: ${param.mode} ${param.typ}")
        }

        // For main function, show the body statements
        if (func.name == "main") {
          println(s"  Body statements:")
          func.body.statements.foreach { stmt =>
            stmt match {
              case LetStatement(varName, typeAnnotation, init, _) =>
                println(s"    let ${varName}: ${typeAnnotation
                    .getOrElse("inferred")} = ${init.getClass.getSimpleName}")
              case ExpressionStatement(expr, _) =>
                println(s"    ${expr.getClass.getSimpleName}")
              case _ =>
                println(s"    ${stmt.getClass.getSimpleName}")
            }
          }
        }
      }
    } catch {
      case e: Exception =>
        System.err.println(s"Error in managed types test: ${e.getMessage}")
        e.printStackTrace()
    }

  }
}
