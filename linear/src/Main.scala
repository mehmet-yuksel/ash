package linear

// --- Core Types ---
enum Type:
  case IntType
  case BoolType
  case UnitType
  case StructName(name: String) // Refers to a struct defined elsewhere

// Parameter Passing Modes
enum ParamMode:
  case Move  // Pass by value, ownership moves (or copies for Copy types)
  case Ref   // Immutable borrow
  case Inout // Mutable borrow (exclusive borrow)

// --- Definitions ---
// Represents a struct definition: struct Point { x: int, y: int }
case class StructDef(name: String, fields: Map[String, Type])

// Represents a function parameter: name: Type (mode)
case class Param(name: String, typ: Type, mode: ParamMode)

// Represents a function definition
case class FuncDef(
    name: String,
    params: List[Param],
    returnType: Type,
    body: List[Statement]
)

// --- Statements ---
enum Statement:
  // let varName: Type = init; (type annotation is optional)
  case Let(varName: String, typeAnnotation: Option[Type], init: Expression)
  // For standalone expressions like function calls: foo();
  case ExpressionStatement(expr: Expression)
  // return expr; or return;
  case Return(expr: Option[Expression])
  // Assignment: target.field = value or target = value (if target is mutable variable)
  // For now, only field assignment for inout params.
  case Assignment(target: Expression, value: Expression)


// --- Expressions ---
enum Expression:
  case IntLiteral(value: Int)
  case BoolLiteral(value: Boolean)
  // Variable usage: x
  case Variable(name: String)
  // Struct instantiation: Point { x: 10, y: 20 }
  case StructLiteral(typeName: String, values: Map[String, Expression])
  // Field access: p.x
  case FieldAccess(obj: Expression, fieldName: String)
  // Function call: foo(arg1, arg2)
  case FunctionCall(funcName: String, args: List[Expression])

// --- Program ---
// A program is a collection of struct and function definitions
case class Program(structs: List[StructDef], functions: List[FuncDef])

// Information about a variable in the current scope
enum VarState:
  case Owned          // Variable owns the value
  case Moved          // Value has been moved from this variable
  case BorrowedRead   // Immutably borrowed (TODO: count or more info if needed)
  case BorrowedWrite  // Mutably borrowed (exclusive)

// Stores type and ownership state of a variable
case class VarInfo(typ: Type, state: VarState)

// Context for type checking
// GlobalContext: Stores definitions of structs and functions
case class GlobalContext(
    structs: Map[String, StructDef] = Map.empty,
    functions: Map[String, FuncDef] = Map.empty // Store full FuncDef for signature
)

// LocalContext: Mutable map for variable types and states within a function scope
// Using fully qualified name for Scala's mutable Map
type LocalContext = scala.collection.mutable.Map[String, VarInfo]

// Custom exception for type errors
case class TypeError(message: String) extends Exception(message)

object Main {
  def main(args: Array[String]): Unit = {
    println("Hello, Scala 3 with Mill!")
  }
}