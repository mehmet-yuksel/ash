package ash.parser

import ash.parser.SourceLocation

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

enum ParamMode:
  case Move(isMutable: Boolean = false)
  case Ref
  case Inout

enum BinaryOp:
  case Add, Sub
  case Lt, Le, Gt, Ge
  case Eq, Ne

case class StructDef(
    name: String,
    fields: List[(String, Type)],
    loc: SourceLocation
)

case class ResourceDef(
    name: String,
    fields: List[(String, Type)],
    cleanup: Option[BlockStatement],
    loc: SourceLocation
)

case class Param(name: String, typ: Type, mode: ParamMode, loc: SourceLocation)

case class FuncDef(
    name: String,
    params: List[Param],
    returnType: Type,
    body: BlockStatement,
    loc: SourceLocation
)

sealed trait Statement { val loc: SourceLocation }

case class BlockStatement(statements: List[Statement], loc: SourceLocation)
    extends Statement

case class LetStatement(
    varName: String,
    isMutable: Boolean,
    typeAnnotation: Option[Type],
    init: Expression,
    loc: SourceLocation
) extends Statement

case class ExpressionStatement(expr: Expression, loc: SourceLocation)
    extends Statement

case class ReturnStatement(expr: Option[Expression], loc: SourceLocation)
    extends Statement

case class AssignmentStatement(
    target: Expression,
    value: Expression,
    loc: SourceLocation
) extends Statement

sealed trait Expression { val loc: SourceLocation }

case class IntLiteral(value: Int, loc: SourceLocation) extends Expression

case class BoolLiteral(value: Boolean, loc: SourceLocation) extends Expression

case class Variable(name: String, loc: SourceLocation) extends Expression

case class StructLiteral(
    typeName: String,
    values: List[(String, Expression)],
    loc: SourceLocation
) extends Expression

case class ManagedStructLiteral(
    typeName: String,
    values: List[(String, Expression)],
    loc: SourceLocation
) extends Expression

case class FieldAccess(obj: Expression, fieldName: String, loc: SourceLocation)
    extends Expression

case class FunctionCall(
    funcName: Expression,
    args: List[Expression],
    loc: SourceLocation
) extends Expression

case class PrintlnExpression(
    formatString: String,
    args: List[Expression],
    loc: SourceLocation
) extends Expression

case class BinaryExpression(
    left: Expression,
    op: BinaryOp,
    right: Expression,
    loc: SourceLocation
) extends Expression

case class Program(
    structs: List[StructDef],
    resources: List[ResourceDef],
    functions: List[FuncDef],
    loc: SourceLocation
)
