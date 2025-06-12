package linear.parser

import scala.util.matching.Regex
import scala.collection.mutable.ArrayBuffer

// --- Data Structures ---
case class SourceLocation(
    line: Int, // 1-indexed line number
    column: Int, // 1-indexed column number
    startPosition: Int, // 0-indexed start offset in input string
    endPosition: Int // 0-indexed end offset in input string (exclusive)
)

case class Token(
    typ: String, // Renamed from 'type' to avoid keyword clash in some contexts
    lexeme: String,
    loc: SourceLocation
)

// --- Error Handling ---
object ErrorUtils {
  def generateErrorPreview(input: String, loc: SourceLocation): String = {
    // Handle different line ending styles and ensure consistent splitting
    val normalizedInput = input.replace("\r\n", "\n").replace("\r", "\n")
    val lines = normalizedInput.split("\n", -1) // -1 to keep empty trailing strings
    
    // Adjust for 0-indexed lines array vs 1-indexed loc.line
    val errorLine = if (loc.line > 0 && loc.line <= lines.length) {
      lines(loc.line - 1)
    } else {
      s"<line ${loc.line} out of range: only ${lines.length} lines available>"
    }
    
    // Adjust for 0-indexed repeat vs 1-indexed loc.column
    // Ensure we don't go negative and handle cases where column is beyond line length
    val column = (loc.column - 1).max(0)
    val caretLine = " " * column.min(errorLine.length) + "^"
    
    // Show line number in the preview for better context
    val lineNumber = f"${loc.line}%3d: "
    val paddingSpaces = " " * lineNumber.length
    
    s"""
Error at line ${loc.line}, column ${loc.column}:
$lineNumber$errorLine
$paddingSpaces$caretLine"""
  }
}

class LexerError(message: String) extends RuntimeException(message)
class ParserError(message: String) extends RuntimeException(message)

// --- Lexer ---
private case class TokenDefinition(typ: String, pattern: Regex)

class Lexer(input: String) {
  private var tokenDefinitionsList: List[TokenDefinition] = List.empty
  private var position: Int = 0
  private var currentLine: Int = 1
  private var currentLineStartPos: Int = 0

  /** Defines a token type with a regex pattern. Patterns are tried in the order
    * they are added. For patterns that could match the same prefix, ensure more
    * specific patterns (e.g., keywords) are added before more general ones
    * (e.g., identifiers) if they can have the same length. The lexer
    * prioritizes the longest match. If multiple patterns yield the longest
    * match of the same length, the one defined earliest (added first to this
    * lexer instance) is chosen.
    */
  def token(typ: String, patternString: String): Lexer = {
    // Regex matching from the beginning of a string slice
    tokenDefinitionsList =
      tokenDefinitionsList :+ TokenDefinition(typ, patternString.r)
    this
  }

  def lex(): Vector[Token] = {
    val result = ArrayBuffer.empty[Token]
    position = 0
    currentLine = 1
    currentLineStartPos = 0

    while (position < input.length) {
      var bestMatch: Option[(TokenDefinition, Regex.Match)] = None
      val currentSubstring = input.substring(position)

      // Find the best (longest) match among all token definitions
      for (tokenDef <- tokenDefinitionsList) {
        tokenDef.pattern.findPrefixMatchOf(currentSubstring) match {
          case Some(m) =>
            if (bestMatch.forall(_._2.matched.length < m.matched.length)) {
              bestMatch = Some((tokenDef, m))
            } else if (
              bestMatch.isDefined && bestMatch.get._2.matched.length == m.matched.length
            ) {
              // If same length, the one defined earlier (already in bestMatch) wins.
              // This loop structure naturally handles it if tokenDefinitionsList is iterated in definition order.
            }
          case None => // No match for this definition
        }
      }

      bestMatch match {
        case Some((matchedTokenDefinition, regexMatch)) =>
          val lexeme = regexMatch.matched
          val tokenStartPosition = position
          val tokenEndPosition = tokenStartPosition + lexeme.length

          if (!matchedTokenDefinition.typ.startsWith("$SKIP")) {
            result += Token(
              matchedTokenDefinition.typ,
              lexeme,
              SourceLocation(
                line = currentLine,
                column = tokenStartPosition - currentLineStartPos + 1,
                startPosition = tokenStartPosition,
                endPosition = tokenEndPosition
              )
            )
          }

          position += lexeme.length

          var i = 0
          while (i < lexeme.length) {
            if (lexeme(i) == '\n') {
              currentLine += 1
              currentLineStartPos = tokenStartPosition + i + 1
            }
            i += 1
          }

        case None =>
          val errorChar = input(position)
          val errorColumn = position - currentLineStartPos + 1
          val loc = SourceLocation(
            line = currentLine,
            column = errorColumn,
            startPosition = position,
            endPosition = position + 1
          )
          val preview = ErrorUtils.generateErrorPreview(input, loc)
          throw new LexerError(
            s"Unexpected character '$errorChar' at line $currentLine, column $errorColumn\n$preview"
          )
      }
    }

    result += Token(
      "EOF",
      "",
      SourceLocation(
        line = currentLine,
        column = position - currentLineStartPos + 1,
        startPosition = position,
        endPosition = position
      )
    )
    result.toVector
  }
}

// --- Parser ---
type PrefixParselet[T] = (Parser[T], Token) => T
type InfixParselet[T] = (Parser[T], T, Token) => T

class Parser[T](private val originalInput: String, lexer: Lexer) {
  private val tokens: Vector[Token] = lexer.lex()
  private var current: Int = 0 // Index of the next token to consume

  private val prefixParselets
      : scala.collection.mutable.Map[String, PrefixParselet[T]] =
    scala.collection.mutable.Map.empty
  private val infixParselets
      : scala.collection.mutable.Map[String, (Int, InfixParselet[T])] =
    scala.collection.mutable.Map.empty

  if (tokens.isEmpty || tokens.last.typ != "EOF") {
    // This case should ideally be prevented by the Lexer always adding EOF.
    throw new IllegalArgumentException(
      "Token stream must end with an EOF token."
    )
  }

  def prefix(tokenType: String, parselet: PrefixParselet[T]): Parser[T] = {
    prefixParselets(tokenType) = parselet
    this
  }

  def infix(
      tokenType: String,
      precedence: Int,
      parselet: InfixParselet[T]
  ): Parser[T] = {
    infixParselets(tokenType) = (precedence, parselet)
    this
  }

  def parseExpression(precedence: Int = 0): T = {
    var token = advance() // Consumes the first token of the expression

    if (token.typ == "EOF") {
      val errorLoc =
        if (current == 1) { // EOF was the very first token advanced
          token.loc
        } else { // current > 1, meaning tokens(current-2) was the token before EOF
          val lastNonEofToken = tokens(current - 2)
          SourceLocation(
            line = lastNonEofToken.loc.line,
            column = lastNonEofToken.loc.column + lastNonEofToken.lexeme.length,
            startPosition = lastNonEofToken.loc.endPosition,
            endPosition = lastNonEofToken.loc.endPosition
          )
        }
      val preview = ErrorUtils.generateErrorPreview(originalInput, errorLoc)
      throw new ParserError(
        s"Unexpected end of input. Expected an expression at line ${errorLoc.line}, column ${errorLoc.column}\n$preview"
      )
    }

    val prefix = prefixParselets.get(token.typ)
    if (prefix.isEmpty) {
      val preview = ErrorUtils.generateErrorPreview(originalInput, token.loc)
      throw new ParserError(
        s"Unexpected token '${token.lexeme}' (type: ${token.typ}). No prefix parselet defined at line ${token.loc.line}, column ${token.loc.column}\n$preview"
      )
    }

    var left = prefix.get(this, token)

    while (precedence < getPrecedence()) {
      token = advance() // Consumes the infix operator token

      val infixEntry = infixParselets.get(token.typ)
      // This should ideally not happen if getPrecedence is correct,
      // as getPrecedence would return 0 for a token with no infix parselet.
      if (infixEntry.isEmpty) {
        val preview = ErrorUtils.generateErrorPreview(originalInput, token.loc)
        throw new ParserError(
          s"Token '${token.lexeme}' (type: ${token.typ}) was encountered in an infix position, but no corresponding infix parselet is defined at line ${token.loc.line}, column ${token.loc.column}\n$preview"
        )
      }
      left = infixEntry.get._2(this, left, token)
    }
    left
  }

  def expect(expectedType: String): Token = {
    val token = advance()
    if (token.typ != expectedType) {
      val message =
        if (token.typ == "EOF")
          s"Unexpected end of input. Expected token type: $expectedType"
        else
          s"Expected token type: $expectedType, but got '${token.lexeme}' (type: ${token.typ})"
      val preview = ErrorUtils.generateErrorPreview(originalInput, token.loc)
      throw new ParserError(
        s"$message at line ${token.loc.line}, column ${token.loc.column}\n$preview"
      )
    }
    token
  }

  /** Checks if the current token matches the expected type. If so, consumes it
    * and returns true. Otherwise, returns false and does not consume the token.
    */
  def matchAndAdvance(expectedType: String): Boolean = {
    if (peek().typ == expectedType) {
      advance()
      true
    } else {
      false
    }
  }

  /** Consumes and returns the current token, advancing the parser. If at the
    * end of input, repeatedly returns the EOF token.
    */
  def advance(): Token = {
    if (current < tokens.length) {
      val token = tokens(current)
      current += 1
      token
    } else {
      tokens.last // Should be EOF token
    }
  }

  /** Returns the current token without consuming it. If at the end of input,
    * returns the EOF token.
    */
  def peek(): Token = {
    if (current < tokens.length) {
      tokens(current)
    } else {
      tokens.last // Should be EOF token
    }
  }

  /** Returns the most recently consumed token. Throws an error if called before
    * any token has been advanced.
    */
  def previous(): Token = {
    if (current == 0)
      throw new IllegalStateException(
        "No previous token: advance() has not been called yet."
      )
    tokens(current - 1)
  }

  private def getPrecedence(): Int = {
    val token = peek()
    // EOF token has 0 precedence, effectively stopping the while loop in parseExpression
    if (token.typ == "EOF") return 0

    infixParselets.get(token.typ) match {
      case Some((p, _)) => p
      case None         => 0 // Tokens not in infixParselets have 0 precedence
    }
  }
}
