package linear.parser

import linear.parser.SourceLocation

// --- Core Types ---
sealed trait Type {
  val loc: Option[SourceLocation] // For types specified in source
  def withLoc(newLoc: SourceLocation): Type
}
case class IntType(loc: Option[SourceLocation] = None) extends Type {
  override def withLoc(newLoc: SourceLocation): Type =
    this.copy(loc = Some(newLoc))
}
case class BoolType(loc: Option[SourceLocation] = None) extends Type {
  override def withLoc(newLoc: SourceLocation): Type =
    this.copy(loc = Some(newLoc))
}
case class UnitType(loc: Option[SourceLocation] = None) extends Type {
  override def withLoc(newLoc: SourceLocation): Type =
    this.copy(loc = Some(newLoc))
}
case class StructNameType(name: String, loc: Option[SourceLocation] = None)
    extends Type {
  override def withLoc(newLoc: SourceLocation): Type =
    this.copy(loc = Some(newLoc))
}
case class ManagedType(innerType: Type, loc: Option[SourceLocation] = None)
    extends Type {
  override def withLoc(newLoc: SourceLocation): Type =
    this.copy(loc = Some(newLoc))
}

// Parameter Passing Modes
enum ParamMode:
  case Move(isMutable: Boolean = false) // Pass by value, ownership moves (or copies for Copy types)
  case Ref // Immutable borrow
  case Inout // Mutable borrow (exclusive borrow)

// --- Definitions ---
// Represents a struct definition: struct Point { x: int, y: int }
case class StructDef(
    name: String,
    fields: List[(String, Type)],
    loc: SourceLocation
)

// Represents a resource definition: resource File { fd: int }
case class ResourceDef(
    name: String,
    fields: List[(String, Type)],
    cleanup: Option[BlockStatement],
    loc: SourceLocation
)

// Represents a function parameter: name: Type (mode)
case class Param(name: String, typ: Type, mode: ParamMode, loc: SourceLocation)

// Represents a function definition
case class FuncDef(
    name: String,
    params: List[Param],
    returnType: Type,
    body: BlockStatement, // Body is now a BlockStatement
    loc: SourceLocation
)

// --- Statements ---
sealed trait Statement { val loc: SourceLocation }
// A block of statements: { stmt1; stmt2; }
case class BlockStatement(statements: List[Statement], loc: SourceLocation)
    extends Statement

// let varName: Type = init; or let mut varName: Type = init; (type annotation is optional)
case class LetStatement(
    varName: String,
    isMutable: Boolean,
    typeAnnotation: Option[Type],
    init: Expression,
    loc: SourceLocation
) extends Statement

// For standalone expressions like function calls: foo();
case class ExpressionStatement(expr: Expression, loc: SourceLocation)
    extends Statement

// return expr; or return;
case class ReturnStatement(expr: Option[Expression], loc: SourceLocation)
    extends Statement

// Assignment: target.field = value or target = value (if target is mutable variable)
case class AssignmentStatement(
    target: Expression,
    value: Expression,
    loc: SourceLocation
) extends Statement

// --- Expressions ---
sealed trait Expression { val loc: SourceLocation }
case class IntLiteral(value: Int, loc: SourceLocation) extends Expression
case class BoolLiteral(value: Boolean, loc: SourceLocation) extends Expression
// Variable usage: x
case class Variable(name: String, loc: SourceLocation) extends Expression
// Struct instantiation: Point { x: 10, y: 20 }
case class StructLiteral(
    typeName: String,
    values: List[(String, Expression)],
    loc: SourceLocation
) extends Expression
// Managed struct instantiation: managed Point { x: 10, y: 20 }
case class ManagedStructLiteral(
    typeName: String,
    values: List[(String, Expression)],
    loc: SourceLocation
) extends Expression
// Field access: p.x
case class FieldAccess(obj: Expression, fieldName: String, loc: SourceLocation)
    extends Expression
// Function call: foo(arg1, arg2)
case class FunctionCall(
    funcName: Expression,
    args: List[Expression],
    loc: SourceLocation
) extends Expression

// --- Program ---
// A program is a collection of struct, resource and function definitions
case class Program(
    structs: List[StructDef],
    resources: List[ResourceDef],
    functions: List[FuncDef],
    loc: SourceLocation
)
