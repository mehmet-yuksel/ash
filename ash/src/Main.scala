package ash

import parser.{
  ExpressionStatement,
  LetStatement,
  ParserError,
  LexerError,
  LanguageParser
}
import ash.typechecker.Typechecker
import ash.typechecker.TypeError
import ash.codegen.CppCodeGenerator
import java.nio.file.{Files, Paths}

object Main {
  def main(args: Array[String]): Unit = {
    if (args.length != 1) {
      println("Usage: ash <source-file>")
      System.exit(1)
    }

    val sourceFile = args(0)
    val sourcePath = Paths.get(sourceFile)
    
    if (!Files.exists(sourcePath)) {
      println(s"Error: File '$sourceFile' not found")
      System.exit(1)
    }

    val sourceCode = new String(Files.readAllBytes(sourcePath), "UTF-8")
    val outputFile = sourceFile.replaceAll("\\.[^.]*$", ".cpp")
    
    try {
      val languageParser = new LanguageParser(sourceCode)
      val programAst = languageParser.parseProgram()
      println(s"Successfully parsed $sourceFile!")
      val typechecker = new Typechecker(programAst, sourceCode)
      val typedProgram = typechecker.check()

      val codegen = new CppCodeGenerator(typedProgram)
      val cppCode = codegen.generate()
      val outputPath = Paths.get(outputFile)
      Files.write(outputPath, cppCode.getBytes("UTF-8"))
      println(s"Generated $outputFile")

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
