package ash.typechecker.typed

import ash.parser.SourceLocation
import ash.parser._

// --- Typed Expressions ---
sealed trait TypedExpression {
  val typ: Type
  val loc: SourceLocation
}

case class TypedIntLiteral(value: Int, typ: Type, loc: SourceLocation)
    extends TypedExpression
case class TypedBoolLiteral(value: Boolean, typ: Type, loc: SourceLocation)
    extends TypedExpression
case class TypedVariable(name: String, typ: Type, loc: SourceLocation)
    extends TypedExpression
case class TypedStructLiteral(
    typeName: String,
    values: List[(String, TypedExpression)],
    typ: Type,
    loc: SourceLocation
) extends TypedExpression
case class TypedManagedStructLiteral(
    typeName: String,
    values: List[(String, TypedExpression)],
    typ: Type,
    loc: SourceLocation
) extends TypedExpression
case class TypedFieldAccess(
    obj: TypedExpression,
    fieldName: String,
    typ: Type,
    loc: SourceLocation
) extends TypedExpression
case class TypedFunctionCall(
    funcName: String, // Simplified to a string for now
    args: List[TypedExpression],
    typ: Type,
    loc: SourceLocation
) extends TypedExpression
case class TypedPrintlnExpression(
    formatString: String,
    args: List[TypedExpression],
    typ: Type,
    loc: SourceLocation
) extends TypedExpression
case class TypedBinaryExpression(
    left: TypedExpression,
    op: BinaryOp,
    right: TypedExpression,
    typ: Type,
    loc: SourceLocation
) extends TypedExpression

// --- Typed Statements ---
sealed trait TypedStatement { val loc: SourceLocation }
case class TypedBlockStatement(
    statements: List[TypedStatement],
    loc: SourceLocation
) extends TypedStatement
case class TypedLetStatement(
    varName: String,
    isMutable: Boolean,
    init: TypedExpression,
    loc: SourceLocation
) extends TypedStatement
case class TypedExpressionStatement(expr: TypedExpression, loc: SourceLocation)
    extends TypedStatement
case class TypedReturnStatement(
    expr: Option[TypedExpression],
    loc: SourceLocation
) extends TypedStatement
case class TypedAssignmentStatement(
    target: TypedExpression,
    value: TypedExpression,
    loc: SourceLocation
) extends TypedStatement

// --- Typed Definitions ---
case class TypedFuncDef(
name: String,
params: List[Param],
returnType: Type,
body: TypedBlockStatement,
loc: SourceLocation
)

case class TypedResourceDef(
  name: String,
  fields: List[(String, Type)],
  cleanup: Option[TypedBlockStatement],
  loc: SourceLocation
)

// --- Typed Program ---
// The final, type-checked representation of the program.
// This is the input to the code generator.
case class TypedProgram(
    structs: List[StructDef],
    resources: List[TypedResourceDef],
    functions: List[TypedFuncDef],
    loc: SourceLocation
)
