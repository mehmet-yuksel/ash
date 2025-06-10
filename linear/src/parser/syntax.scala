package linear.parser

import linear._ // Your AST definitions
import scala.collection.mutable.ListBuffer

object Precedence {
  val LOWEST = 0
  val ASSIGNMENT = 1 // =
  val LOGICAL_OR = 2 // or
  val LOGICAL_AND = 3 // and
  val EQUALITY = 4 // == !=
  val COMPARISON = 5 // < > <= >=
  val TERM = 6 // + -
  val FACTOR = 7 // * /
  val UNARY = 8 // ! -
  val CALL = 9 // . ()
  val PRIMARY = 10
}

class LanguageParser(input: String) {
  private val lexer = new Lexer(input)
    // Comments
    .token("$SKIP_LINE_COMMENT", "//.*")
    .token(
      "$SKIP_BLOCK_COMMENT",
      "/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/"
    ) // Non-greedy block comment
    .token("$SKIP_WHITESPACE", "[ \\t\\r\\n]+")

    // Keywords
    .token("STRUCT", "struct\\b")
    .token("RESOURCE", "resource\\b")
    .token("FN", "fn\\b")
    .token("LET", "let\\b")
    .token("RETURN", "return\\b")
    .token("REF", "ref\\b")
    .token("INOUT", "inout\\b")
    .token("MANAGED", "managed\\b")
    .token("INT_TYPE", "int\\b")
    .token("BOOL_TYPE", "bool\\b")
    .token("UNIT_TYPE", "unit\\b")
    .token("TRUE", "true\\b")
    .token("FALSE", "false\\b")
    // TODO: Add other keywords like if, else, while etc. later

    // Identifiers
    .token("IDENTIFIER", "[a-zA-Z_][a-zA-Z0-9_]*")

    // Literals
    .token("INT_LITERAL", "[0-9]+")
    // String literals would go here if needed

    // Operators and Punctuation
    .token("LPAREN", "\\(")
    .token("RPAREN", "\\)")
    .token("LBRACE", "\\{")
    .token("RBRACE", "\\}")
    // .token("LBRACKET", "\\[") // For arrays if added
    // .token("RBRACKET", "\\]")
    .token("COMMA", ",")
    .token("DOT", "\\.")
    .token("COLON", ":")
    .token("SEMICOLON", ";")
    .token("ARROW", "->")
    .token("EQUALS", "=")
  // Add other operators like +, -, *, /, ==, !=, <, >, etc.

  private val parser = new Parser[Expression](input, lexer)

  // Register prefix parselets
  parser
    .prefix("IDENTIFIER", parseIdentifier)
    .prefix("INT_LITERAL", parseIntLiteral)
    .prefix("TRUE", parseBooleanLiteral)
    .prefix("FALSE", parseBooleanLiteral)
    .prefix("LPAREN", parseGroupedExpression)
    .prefix("MANAGED", parseManagedExpression)
  // Prefix parselets for struct literals are handled by parseIdentifier
  // when it sees an IDENTIFIER followed by LBRACE

  // Register infix parselets
  parser
    .infix("LPAREN", Precedence.CALL, parseFunctionCall) // e.g. foo()
    .infix("DOT", Precedence.CALL, parseFieldAccess) // e.g. obj.field
  // Add other infix operators here:
  // .infix("PLUS", Precedence.TERM, parseBinaryOperator)
  // .infix("EQUALS", Precedence.ASSIGNMENT, parseAssignmentExpression) // If assignment is an expression

  // --- Expression Parsing Methods ---
  private def parseIdentifier(
      p: Parser[Expression],
      token: Token
  ): Expression = {
    // Check if it's a struct literal instantiation
    if (p.peek().typ == "LBRACE") {
      parseStructLiteral(p, token)
    } else {
      Variable(token.lexeme, token.loc)
    }
  }

  private def parseIntLiteral(
      p: Parser[Expression],
      token: Token
  ): Expression = {
    try {
      IntLiteral(token.lexeme.toInt, token.loc)
    } catch {
      case e: NumberFormatException =>
        val preview = ErrorUtils.generateErrorPreview(input, token.loc)
        throw new ParserError(
          s"Invalid integer literal: '${token.lexeme}' at line ${token.loc.line}, column ${token.loc.column}\n$preview"
        )
    }
  }

  private def parseBooleanLiteral(
      p: Parser[Expression],
      token: Token
  ): Expression = {
    BoolLiteral(token.lexeme == "true", token.loc)
  }

  private def parseGroupedExpression(
      p: Parser[Expression],
      token: Token
  ): Expression = {
    val expr = p.parseExpression(Precedence.LOWEST)
    p.expect("RPAREN")
    expr // Location of grouped expr is implicitly covered by its content + parens
  }

  private def parseManagedExpression(
      p: Parser[Expression],
      token: Token
  ): Expression = {
    // token is the "managed" keyword
    // Next should be an IDENTIFIER followed by LBRACE for struct literal
    val typeNameToken = p.expect("IDENTIFIER")
    if (p.peek().typ == "LBRACE") {
      parseManagedStructLiteral(p, token, typeNameToken)
    } else {
      val preview = ErrorUtils.generateErrorPreview(input, typeNameToken.loc)
      throw new ParserError(
        s"Expected struct literal after 'managed ${typeNameToken.lexeme}' but got '${p
            .peek()
            .lexeme}' at line ${p.peek().loc.line}, column ${p.peek().loc.column}\n$preview"
      )
    }
  }

  private def parseStructLiteral(
      p: Parser[Expression],
      typeNameToken: Token
  ): Expression = {
    p.expect("LBRACE") // Consume the opening brace
    val fields = ListBuffer.empty[(String, Expression)]
    val firstTokenLoc = typeNameToken.loc

    if (p.peek().typ != "RBRACE") {
      val fieldNameToken = p.expect("IDENTIFIER")
      p.expect("COLON")
      val value = p.parseExpression(Precedence.LOWEST)
      fields += ((fieldNameToken.lexeme, value))
      while (p.matchAndAdvance("COMMA") && p.peek().typ != "RBRACE") {
        val fieldNameToken = p.expect("IDENTIFIER")
        p.expect("COLON")
        val value = p.parseExpression(Precedence.LOWEST)
        fields += ((fieldNameToken.lexeme, value))
      }
    }
    val rBraceToken = p.expect("RBRACE")
    val loc = SourceLocation(
      firstTokenLoc.line,
      firstTokenLoc.column,
      firstTokenLoc.startPosition,
      rBraceToken.loc.endPosition
    )
    StructLiteral(typeNameToken.lexeme, fields.toList, loc)
  }

  private def parseManagedStructLiteral(
      p: Parser[Expression],
      managedToken: Token,
      typeNameToken: Token
  ): Expression = {
    p.expect("LBRACE") // Consume the opening brace
    val fields = ListBuffer.empty[(String, Expression)]
    val firstTokenLoc = managedToken.loc

    if (p.peek().typ != "RBRACE") {
      val fieldNameToken = p.expect("IDENTIFIER")
      p.expect("COLON")
      val value = p.parseExpression(Precedence.LOWEST)
      fields += ((fieldNameToken.lexeme, value))
      while (p.matchAndAdvance("COMMA") && p.peek().typ != "RBRACE") {
        val fieldNameToken = p.expect("IDENTIFIER")
        p.expect("COLON")
        val value = p.parseExpression(Precedence.LOWEST)
        fields += ((fieldNameToken.lexeme, value))
      }
    }
    val rBraceToken = p.expect("RBRACE")
    val loc = SourceLocation(
      firstTokenLoc.line,
      firstTokenLoc.column,
      firstTokenLoc.startPosition,
      rBraceToken.loc.endPosition
    )
    ManagedStructLiteral(typeNameToken.lexeme, fields.toList, loc)
  }

  private def parseFunctionCall(
      p: Parser[Expression],
      left: Expression,
      token: Token
  ): Expression = {
    val args = ListBuffer.empty[Expression]
    // 'left' is the function name (or expression evaluating to a function)
    // 'token' is LPAREN

    if (p.peek().typ != "RPAREN") {
      args += p.parseExpression(Precedence.LOWEST)
      while (p.matchAndAdvance("COMMA")) {
        args += p.parseExpression(Precedence.LOWEST)
      }
    }
    val rParenToken = p.expect("RPAREN")
    val endLoc = rParenToken.loc.endPosition
    val callLoc = SourceLocation(
      left.loc.line,
      left.loc.column,
      left.loc.startPosition,
      endLoc
    )
    FunctionCall(left, args.toList, callLoc)
  }

  private def parseFieldAccess(
      p: Parser[Expression],
      left: Expression,
      token: Token
  ): Expression = {
    // 'left' is the object
    // 'token' is DOT
    val fieldNameToken = p.expect("IDENTIFIER")
    val loc = SourceLocation(
      left.loc.line,
      left.loc.column,
      left.loc.startPosition,
      fieldNameToken.loc.endPosition
    )
    FieldAccess(left, fieldNameToken.lexeme, loc)
  }

  // --- Type Parsing ---
  private def parseType(p: Parser[Expression]): Type = {
    if (p.peek().typ == "MANAGED") {
      val managedToken = p.advance() // consume 'managed'
      val innerType = parseBaseType(p)
      val endLoc = innerType.loc.getOrElse(p.previous().loc)
      val managedLoc = SourceLocation(
        managedToken.loc.line,
        managedToken.loc.column,
        managedToken.loc.startPosition,
        endLoc.endPosition
      )
      ManagedType(innerType, Some(managedLoc))
    } else {
      parseBaseType(p)
    }
  }

  private def parseBaseType(p: Parser[Expression]): Type = {
    val typeToken = p.advance()
    typeToken.typ match {
      case "INT_TYPE"   => IntType(Some(typeToken.loc))
      case "BOOL_TYPE"  => BoolType(Some(typeToken.loc))
      case "UNIT_TYPE"  => UnitType(Some(typeToken.loc))
      case "IDENTIFIER" => StructNameType(typeToken.lexeme, Some(typeToken.loc))
      case _ =>
        val preview = ErrorUtils.generateErrorPreview(input, typeToken.loc)
        throw new ParserError(
          s"Expected a base type name (int, bool, unit, or Identifier) but got '${typeToken.lexeme}' at line ${typeToken.loc.line}, column ${typeToken.loc.column}\n$preview"
        )
    }
  }

  // --- Statement Parsing ---
  private def parseStatement(p: Parser[Expression]): Statement = {
    val currentToken = p.peek()
    currentToken.typ match {
      case "LET"    => parseLetStatement(p)
      case "RETURN" => parseReturnStatement(p)
      case "LBRACE" => parseBlockStatement(p)
      // Attempt to parse an expression statement (could be assignment or function call)
      case _ =>
        val expr = p.parseExpression(Precedence.LOWEST)
        // Check if it's an assignment
        if (p.peek().typ == "EQUALS") {
          parseAssignmentStatement(p, expr) // expr is the target
        } else {
          p.expect("SEMICOLON")
          ExpressionStatement(expr, expr.loc) // loc from the expression itself
        }
    }
  }

  private def parseLetStatement(p: Parser[Expression]): LetStatement = {
    val letToken = p.expect("LET")
    val varNameToken = p.expect("IDENTIFIER")
    val typeAnnotation = if (p.matchAndAdvance("COLON")) {
      Some(parseType(p))
    } else {
      None
    }
    p.expect("EQUALS")
    val initExpr = p.parseExpression(Precedence.LOWEST)
    p.expect("SEMICOLON")
    LetStatement(varNameToken.lexeme, typeAnnotation, initExpr, letToken.loc)
  }

  private def parseReturnStatement(p: Parser[Expression]): ReturnStatement = {
    val returnToken = p.expect("RETURN")
    val expr = if (p.peek().typ != "SEMICOLON") {
      Some(p.parseExpression(Precedence.LOWEST))
    } else {
      None
    }
    p.expect("SEMICOLON")
    ReturnStatement(expr, returnToken.loc)
  }

  private def parseAssignmentStatement(
      p: Parser[Expression],
      target: Expression
  ): AssignmentStatement = {
    val equalsToken = p.expect("EQUALS") // Consumes the '='
    val valueExpr = p.parseExpression(Precedence.LOWEST)
    p.expect("SEMICOLON")
    // The location should span from the start of the target to the end of the semicolon
    val endLoc = p.previous().loc // Semicolon token
    val assignLoc = SourceLocation(
      target.loc.line,
      target.loc.column,
      target.loc.startPosition,
      endLoc.endPosition
    )
    AssignmentStatement(target, valueExpr, assignLoc)
  }

  private def parseBlockStatement(p: Parser[Expression]): BlockStatement = {
    val lBraceToken = p.expect("LBRACE")
    val statements = ListBuffer.empty[Statement]
    while (p.peek().typ != "RBRACE" && p.peek().typ != "EOF") {
      statements += parseStatement(p)
    }
    val rBraceToken = p.expect("RBRACE")
    val loc = SourceLocation(
      lBraceToken.loc.line,
      lBraceToken.loc.column,
      lBraceToken.loc.startPosition,
      rBraceToken.loc.endPosition
    )
    BlockStatement(statements.toList, loc)
  }

  // --- Top-Level Parsing (Program, Structs, Resources, Functions) ---
  private def parseStructDef(p: Parser[Expression]): StructDef = {
    val structToken = p.expect("STRUCT")
    val nameToken = p.expect("IDENTIFIER")
    p.expect("LBRACE")
    val fields = ListBuffer.empty[(String, Type)]
    while (p.peek().typ != "RBRACE" && p.peek().typ != "EOF") {
      val fieldNameToken = p.expect("IDENTIFIER")
      p.expect("COLON")
      val fieldType = parseType(p)
      fields += ((fieldNameToken.lexeme, fieldType))
      if (p.peek().typ == "RBRACE") {
        // allow trailing comma if we wanted: p.matchAndAdvance("COMMA")
      } else {
        p.expect("COMMA") // Require comma if not the last field before RBRACE
      }
    }
    val rBraceToken = p.expect("RBRACE")
    val loc = SourceLocation(
      structToken.loc.line,
      structToken.loc.column,
      structToken.loc.startPosition,
      rBraceToken.loc.endPosition
    )
    StructDef(nameToken.lexeme, fields.toList, loc)
  }

  private def parseResourceDef(p: Parser[Expression]): ResourceDef = {
    val resourceToken = p.expect("RESOURCE")
    val nameToken = p.expect("IDENTIFIER")
    p.expect("LBRACE")
    val fields = ListBuffer.empty[(String, Type)]
    while (p.peek().typ != "RBRACE" && p.peek().typ != "EOF") {
      val fieldNameToken = p.expect("IDENTIFIER")
      p.expect("COLON")
      val fieldType = parseType(p)
      fields += ((fieldNameToken.lexeme, fieldType))
      if (p.peek().typ == "RBRACE") {
        // allow trailing comma if we wanted: p.matchAndAdvance("COMMA")
      } else {
        p.expect("COMMA") // Require comma if not the last field before RBRACE
      }
    }
    val rBraceToken = p.expect("RBRACE")
    val loc = SourceLocation(
      resourceToken.loc.line,
      resourceToken.loc.column,
      resourceToken.loc.startPosition,
      rBraceToken.loc.endPosition
    )
    ResourceDef(nameToken.lexeme, fields.toList, loc)
  }

  private def parseParam(p: Parser[Expression]): Param = {
    val nameToken = p.expect("IDENTIFIER")
    p.expect("COLON")

    val modePeekToken =
      p.peek() // Peek at what might be 'ref', 'inout', or the base type

    val mode = modePeekToken.typ match {
      case "REF" =>
        p.advance() // Consume 'ref'
        ParamMode.Ref
      case "INOUT" =>
        p.advance() // Consume 'inout'
        ParamMode.Inout
      case _ =>
        ParamMode.Move // No mode keyword, next token is the base type
    }

    val baseTypeAst = parseType(
      p
    ) // Parse type (potentially with managed prefix)

    // Calculate the overall location for the Param AST node
    // It should span from the nameToken to the end of the baseTypeAst
    val paramStartLoc = nameToken.loc
    val paramEndLoc = baseTypeAst.loc.getOrElse(
      p.previous().loc
    ) // Use baseType's loc, or last consumed token if baseType has no loc

    val overallParamLoc = SourceLocation(
      paramStartLoc.line,
      paramStartLoc.column,
      paramStartLoc.startPosition,
      paramEndLoc.endPosition
    )

    Param(nameToken.lexeme, baseTypeAst, mode, overallParamLoc)
  }

  private def parseFuncDef(p: Parser[Expression]): FuncDef = {
    val fnToken = p.expect("FN")
    val nameToken = p.expect("IDENTIFIER")
    p.expect("LPAREN")
    val params = ListBuffer.empty[Param]
    if (p.peek().typ != "RPAREN") {
      params += parseParam(p)
      while (p.matchAndAdvance("COMMA")) {
        params += parseParam(p)
      }
    }
    p.expect("RPAREN")
    val returnType = if (p.matchAndAdvance("ARROW")) {
      parseType(p)
    } else {
      UnitType(None) // Default return type is unit, loc can be None or inferred
    }
    val body = parseBlockStatement(p)
    val loc = SourceLocation(
      fnToken.loc.line,
      fnToken.loc.column,
      fnToken.loc.startPosition,
      body.loc.endPosition
    )
    FuncDef(nameToken.lexeme, params.toList, returnType, body, loc)
  }

  def parseProgram(): Program = {
    val structs = ListBuffer.empty[StructDef]
    val resources = ListBuffer.empty[ResourceDef]
    val functions = ListBuffer.empty[FuncDef]
    val startLoc = parser.peek().loc // Location of the first token

    while (parser.peek().typ != "EOF") {
      parser.peek().typ match {
        case "STRUCT"   => structs += parseStructDef(parser)
        case "RESOURCE" => resources += parseResourceDef(parser)
        case "FN"       => functions += parseFuncDef(parser)
        case other =>
          val token = parser.peek()
          val preview = ErrorUtils.generateErrorPreview(input, token.loc)
          throw new ParserError(
            s"Expected 'struct', 'resource', or 'fn' definition at top level, but got '${token.lexeme}' (type: ${token.typ}) at line ${token.loc.line}, column ${token.loc.column}\n$preview"
          )
      }
    }
    val endLoc =
      parser
        .previous()
        .loc // Location of the token before EOF (or EOF itself if empty)
    val programLoc = SourceLocation(
      startLoc.line,
      startLoc.column,
      startLoc.startPosition,
      endLoc.endPosition
    )
    Program(structs.toList, resources.toList, functions.toList, programLoc)
  }
}
