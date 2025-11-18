package ash.parser

import ash._

object Tokens {
  val ID = "IDENTIFIER"; val INT = "INT_LITERAL"; val STR = "STRING_LITERAL"
  val LPAREN = "LPAREN"; val RPAREN = "RPAREN"
  val LBRACE = "LBRACE"; val RBRACE = "RBRACE"
  val COLON = "COLON"
  val EOF = "EOF"
}

object Precedence {
  val LOWEST = 0; val ASSIGN = 1; val LOGIC = 2; val EQ = 4
  val CMP = 5; val SUM = 6; val PROD = 7; val CALL = 9
}

class LanguageParser(input: String) {
  import Tokens._
  import Precedence._

  private val lexer = new Lexer(input)
    .token("$SKIP_COMMENT", "//.*|/\\*[\\s\\S]*?\\*/")
    .token("$SKIP_WS", "\\s+")
    .token("STRUCT", "struct\\b").token("RESOURCE", "resource\\b").token("FN", "fn\\b")
    .token("LET", "let\\b").token("MUT", "mut\\b").token("RETURN", "return\\b")
    .token("REF", "ref\\b").token("INOUT", "inout\\b").token("MANAGED", "managed\\b")
    .token("CLEANUP", "cleanup\\b").token("PRINTLN", "println!")
    .token("BOOL", "true\\b|false\\b")
    .token("TYPE_PRIM", "int\\b|bool\\b|unit\\b")
    .token(ID, "[a-zA-Z_][a-zA-Z0-9_]*")
    .token(INT, "[0-9]+")
    .token(STR, "\"[^\"]*\"")
    .token("ARROW", "->").token("EQ_OP", "==").token("NE", "!=")
    .token("LE", "<=").token("GE", ">=").token("LT", "<").token("GT", ">")
    .token("EQUALS", "=").token("PLUS", "\\+").token("MINUS", "-")
    .token(LPAREN, "\\(").token(RPAREN, "\\)").token(LBRACE, "\\{").token(RBRACE, "\\}")
    .token("COMMA", ",").token("DOT", "\\.").token("COLON", ":").token("SEMICOLON", ";")

  private val p = new Parser[Expression](input, lexer)

  p.registerPrefix(INT, t => {
    try {
      IntLiteral(t.lexeme.toInt, t.loc)
    } catch {
      case _: NumberFormatException =>
        ErrorUtils.error(s"Invalid integer literal: ${t.lexeme}", t.loc, input)
    }
  })

  p.registerPrefix("BOOL", t => BoolLiteral(t.lexeme == "true", t.loc))
  p.registerPrefix(LPAREN, _ => {
    val expr = p.parseExpression(LOWEST)
    p.consume(RPAREN)
    expr
  })

  p.registerPrefix(ID, t => {
    if (p.check(LBRACE)) parseStructLiteral(t.lexeme, t.loc)
    else Variable(t.lexeme, t.loc)
  })

  p.registerPrefix("MANAGED", t => {
    val name = p.consume(ID)
    if (p.check(LBRACE)) parseStructLiteral(name.lexeme, t.loc, isManaged = true)
    else ErrorUtils.error("Expected struct literal after 'managed'", name.loc, input)
  })

  p.registerPrefix("PRINTLN", t => {
    p.consume(LPAREN)
    val fmt = p.consume(STR).lexeme.stripPrefix("\"").stripSuffix("\"")
    val args = if (p.matchToken("COMMA")) {
      p.parseList(RPAREN, () => p.parseExpression(LOWEST))
    } else {
      p.consume(RPAREN)
      List.empty
    }
    PrintlnExpression(fmt, args, t.loc)
  })

  p.registerInfix(LPAREN, CALL, (left, t) => {
    val args = p.parseList(RPAREN, () => p.parseExpression(LOWEST))
    FunctionCall(left, args, combineLoc(left.loc, p.prev().loc))
  })

  p.registerInfix("DOT", CALL, (left, t) => {
    val field = p.consume(ID)
    FieldAccess(left, field.lexeme, combineLoc(left.loc, field.loc))
  })

  val binOps = Map(
    "PLUS" -> (SUM, BinaryOp.Add), "MINUS" -> (SUM, BinaryOp.Sub),
    "EQ_OP" -> (EQ, BinaryOp.Eq), "NE" -> (EQ, BinaryOp.Ne),
    "LT" -> (CMP, BinaryOp.Lt), "LE" -> (CMP, BinaryOp.Le),
    "GT" -> (CMP, BinaryOp.Gt), "GE" -> (CMP, BinaryOp.Ge)
  )

  binOps.foreach { case (tok, (prec, op)) =>
    p.registerInfix(tok, prec, (left, t) => {
      val right = p.parseExpression(prec)
      BinaryExpression(left, op, right, combineLoc(left.loc, right.loc))
    })
  }

  private def combineLoc(start: SourceLocation, end: SourceLocation) =
    SourceLocation(start.line, start.column, start.startPosition, end.endPosition)

  private def parseStructLiteral(name: String, startLoc: SourceLocation, isManaged: Boolean = false): Expression = {
    p.consume(LBRACE)
    val fields = p.parseList(RBRACE, () => {
      val key = p.consume(ID).lexeme
      p.consume(COLON)
      (key, p.parseExpression(LOWEST))
    })
    val loc = combineLoc(startLoc, p.prev().loc)
    if (isManaged) ManagedStructLiteral(name, fields, loc)
    else StructLiteral(name, fields, loc)
  }

  private def parseType(): Type = {
    if (p.matchToken("MANAGED")) {
      val start = p.prev().loc
      val inner = parseBaseType()
      ManagedType(inner, Some(combineLoc(start, inner.loc.get)))
    } else parseBaseType()
  }

  private def parseBaseType(): Type = {
    val t = p.advance()
    t.typ match {
      case "TYPE_PRIM" => t.lexeme match {
        case "int" => IntType(Some(t.loc))
        case "bool" => BoolType(Some(t.loc))
        case "unit" => UnitType(Some(t.loc))
      }
      case ID => StructNameType(t.lexeme, Some(t.loc))
      case _ => ErrorUtils.error(s"Expected type, got ${t.lexeme}", t.loc, input)
    }
  }

  private def parseStatement(): Statement = p.peek().typ match {
    case "LET" =>
      val start = p.consume("LET").loc
      val isMut = p.matchToken("MUT")
      val name = p.consume(ID).lexeme
      val typeAnn = if (p.matchToken(COLON)) Some(parseType()) else None
      p.consume("EQUALS")
      val expr = p.parseExpression(LOWEST)
      p.consume("SEMICOLON")
      LetStatement(name, isMut, typeAnn, expr, start)

    case "RETURN" =>
      val start = p.consume("RETURN").loc
      val expr = if (!p.check("SEMICOLON")) Some(p.parseExpression(LOWEST)) else None
      p.consume("SEMICOLON")
      ReturnStatement(expr, start)

    case LBRACE => parseBlock()

    case _ =>
      val expr = p.parseExpression(LOWEST)
      if (p.matchToken("EQUALS")) {
        val value = p.parseExpression(LOWEST)
        p.consume("SEMICOLON")
        AssignmentStatement(expr, value, combineLoc(expr.loc, p.prev().loc))
      } else {
        p.consume("SEMICOLON")
        ExpressionStatement(expr, expr.loc)
      }
  }

  private def parseBlock(): BlockStatement = {
    val start = p.consume(LBRACE).loc
    val stmts = collection.mutable.Buffer[Statement]()
    while (!p.check(RBRACE) && !p.check(EOF)) {
      stmts += parseStatement()
    }
    val end = p.consume(RBRACE).loc
    BlockStatement(stmts.toList, combineLoc(start, end))
  }

  def parseProgram(): Program = {
    val structs = collection.mutable.Buffer[StructDef]()
    val resources = collection.mutable.Buffer[ResourceDef]()
    val funcs = collection.mutable.Buffer[FuncDef]()
    val start = p.peek().loc

    while (!p.check(EOF)) {
      p.peek().typ match {
        case "STRUCT" =>
          val start = p.consume("STRUCT").loc
          val name = p.consume(ID).lexeme
          p.consume(LBRACE)
          val fields = p.parseList(RBRACE, () => parseFieldDef())
          structs += StructDef(name, fields, combineLoc(start, p.prev().loc))

        case "RESOURCE" =>
          val start = p.consume("RESOURCE").loc
          val name = p.consume(ID).lexeme
          p.consume(LBRACE)
          val fields = collection.mutable.Buffer[(String, Type)]()
          while (!p.check(RBRACE) && !p.check("CLEANUP") && !p.check(EOF)) {
            fields += parseFieldDef()
            if (!p.check(RBRACE) && !p.check("CLEANUP")) p.consume("COMMA")
          }
          val cleanup = if (p.matchToken("CLEANUP")) Some(parseBlock()) else None
          val end = p.consume(RBRACE).loc
          resources += ResourceDef(name, fields.toList, cleanup, combineLoc(start, end))

        case "FN" =>
          val start = p.consume("FN").loc
          val name = p.consume(ID).lexeme
          p.consume(LPAREN)
          val params = p.parseList(RPAREN, () => {
             val pname = p.consume(ID)
             p.consume(COLON)
             val mode = if (p.matchToken("MUT")) ParamMode.Move(true)
                        else if (p.matchToken("REF")) ParamMode.Ref
                        else if (p.matchToken("INOUT")) ParamMode.Inout
                        else ParamMode.Move(false)
             val ptype = parseType()
             Param(pname.lexeme, ptype, mode, combineLoc(pname.loc, ptype.loc.getOrElse(p.prev().loc)))
          })
          val retType = if (p.matchToken("ARROW")) parseType() else UnitType(None)
          val body = parseBlock()
          funcs += FuncDef(name, params, retType, body, combineLoc(start, body.loc))

        case _ => ErrorUtils.error(s"Unexpected token: ${p.peek().lexeme}", p.peek().loc, input)
      }
    }
    Program(structs.toList, resources.toList, funcs.toList, combineLoc(start, p.prev().loc))
  }

  private def parseFieldDef(): (String, Type) = {
    val name = p.consume(ID).lexeme
    p.consume(COLON)
    (name, parseType())
  }
}
