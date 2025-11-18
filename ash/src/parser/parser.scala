package ash.parser

import scala.util.matching.Regex
import scala.collection.mutable

case class SourceLocation(line: Int, column: Int, startPosition: Int, endPosition: Int)
case class Token(typ: String, lexeme: String, loc: SourceLocation)

class LexerError(msg: String) extends RuntimeException(msg)
class ParserError(msg: String) extends RuntimeException(msg)

object ErrorUtils {
  def generateErrorPreview(input: String, loc: SourceLocation): String = {
    // Handle different line ending styles
    val normalizedInput = input.replace("\r\n", "\n").replace("\r", "\n")
    val lines = normalizedInput.split("\n", -1)

    val errorLine = if (loc.line > 0 && loc.line <= lines.length) {
      lines(loc.line - 1)
    } else {
      ""
    }

    val column = (loc.column - 1).max(0)
    // Ensure caret doesn't go way past the line if line is empty or short
    val caretLine = " " * column.min(errorLine.length) + "^"

    val lineNumber = f"${loc.line}%3d: "
    val padding = " " * lineNumber.length

    s"""
Error at line ${loc.line}, column ${loc.column}:
$lineNumber$errorLine
$padding$caretLine"""
  }

  def error(msg: String, loc: SourceLocation, input: String): Nothing = {
    val preview = generateErrorPreview(input, loc)
    throw new ParserError(s"$msg$preview")
  }
}

private case class TokenDef(typ: String, pattern: Regex)

class Lexer(input: String) {
  private val rules = mutable.Buffer[TokenDef]()

  def token(typ: String, regex: String): Lexer = {
    // ^ anchor ensures we match from the current position
    rules += TokenDef(typ, s"^($regex)".r)
    this
  }

  def lex(): Vector[Token] = {
    var pos = 0
    var line = 1
    var lineStart = 0
    val tokens = mutable.Buffer[Token]()

    while (pos < input.length) {
      val tail = input.substring(pos)

      // Find longest match
      val matchResult = rules.view
        .map(r => (r, r.pattern.findFirstMatchIn(tail)))
        .collect { case (r, Some(m)) => (r, m) }
        .maxByOption(_._2.end) // Pick the one that consumes the most characters

      matchResult match {
        case Some((rule, m)) =>
          val text = m.group(1)
          val len = text.length

          if (!rule.typ.startsWith("$SKIP")) {
            tokens += Token(
              rule.typ,
              text,
              SourceLocation(line, pos - lineStart + 1, pos, pos + len)
            )
          }

          pos += len
          val newLines = text.count(_ == '\n')
          if (newLines > 0) {
            line += newLines
            // Find the position of the last newline in the match relative to current pos
            lineStart = pos - text.reverse.indexOf('\n') - 1
          }

        case None =>
          ErrorUtils.error(
            s"Unexpected character '${input(pos)}'",
            SourceLocation(line, pos - lineStart + 1, pos, pos + 1),
            input
          )
      }
    }

    tokens += Token("EOF", "", SourceLocation(line, pos - lineStart + 1, pos, pos))
    tokens.toVector
  }
}

class Parser[T](private val input: String, lexer: Lexer) {
  private val tokens = lexer.lex()
  private var current = 0

  type PrefixFn = Token => T
  type InfixFn = (T, Token) => T

  private val prefixParselets = mutable.Map[String, PrefixFn]()
  private val infixParselets = mutable.Map[String, (Int, InfixFn)]()

  def registerPrefix(typ: String, fn: PrefixFn): Unit = prefixParselets(typ) = fn
  def registerInfix(typ: String, prec: Int, fn: InfixFn): Unit = infixParselets(typ) = (prec, fn)

  def peek(): Token = {
    if (current < tokens.length) tokens(current) else tokens.last
  }

  def prev(): Token = {
    if (current > 0) tokens(current - 1) else tokens.head
  }

  def advance(): Token = {
    val t = peek()
    if (current < tokens.length) current += 1
    t
  }

  def consume(typ: String): Token = {
    if (check(typ)) advance()
    else ErrorUtils.error(s"Expected '$typ', but got '${peek().typ}'", peek().loc, input)
  }

  def check(typ: String): Boolean = peek().typ == typ

  def matchToken(typ: String): Boolean = {
    if (check(typ)) { advance(); true } else false
  }

  // Parses: item, item, item <EndToken>
  def parseList[A](endToken: String, parseItem: () => A): List[A] = {
    val list = mutable.Buffer[A]()
    if (!check(endToken)) {
      list += parseItem()
      while (matchToken("COMMA")) {
        list += parseItem()
      }
    }
    consume(endToken)
    list.toList
  }

  def parseExpression(precedence: Int = 0): T = {
    val t = advance()

    val prefix = prefixParselets.getOrElse(t.typ,
      ErrorUtils.error(s"Unexpected token '${t.lexeme}' at start of expression.", t.loc, input)
    )

    var left = prefix(t)

    while (precedence < getPrecedence(peek().typ)) {
      val op = advance()
      val (prec, infix) = infixParselets(op.typ)
      left = infix(left, op)
    }
    left
  }

  private def getPrecedence(typ: String): Int = {
    infixParselets.get(typ).map(_._1).getOrElse(0)
  }
}
