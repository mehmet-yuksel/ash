package linear.typechecker

import linear.parser._
import scala.collection.mutable

enum VarState:
  case Owned // Variable owns the value
  case Moved // Value has been moved from this variable
  case BorrowedRead // Immutably borrowed
  case BorrowedWrite // Mutably borrowed

// Stores type and ownership state of a variable
case class VarInfo(
    typ: Type,
    state: VarState,
    isMutable: Boolean, // True if the variable itself can be reassigned (e.g. inout params)
    definitionLoc: SourceLocation
)

// Context for type checking
case class GlobalContext(
    structs: Map[String, StructDef] = Map.empty,
    resources: Map[String, ResourceDef] = Map.empty,
    functions: Map[String, FuncDef] = Map.empty
)

type LocalContext = mutable.Map[String, VarInfo]
case class TypeError(message: String, loc: Option[SourceLocation] = None)
    extends Exception(message)

class Typechecker(program: Program) {

  // Build the global context from top-level definitions
  private val globalContext: GlobalContext = {
    // Check for duplicate struct names
    val duplicateStruct = program.structs.groupBy(_.name).find(_._2.size > 1)
    duplicateStruct.foreach { case (name, defs) =>
      throw TypeError(
        s"Struct '$name' is defined multiple times.",
        Some(defs(1).loc)
      )
    }

    // Check for duplicate resource names
    val duplicateResource =
      program.resources.groupBy(_.name).find(_._2.size > 1)
    duplicateResource.foreach { case (name, defs) =>
      throw TypeError(
        s"Resource '$name' is defined multiple times.",
        Some(defs(1).loc)
      )
    }

    // Check for duplicate function names
    val duplicateFunction =
      program.functions.groupBy(_.name).find(_._2.size > 1)
    duplicateFunction.foreach { case (name, defs) =>
      throw TypeError(
        s"Function '$name' is defined multiple times.",
        Some(defs(1).loc)
      )
    }

    GlobalContext(
      program.structs.map(s => s.name -> s).toMap,
      program.resources.map(r => r.name -> r).toMap,
      program.functions.map(f => f.name -> f).toMap
    )
  }

  /** Public entry point to start the type checking process. */
  def check(): Unit = {
    val mainFunc = globalContext.functions.getOrElse(
      "main",
      throw TypeError("No 'main' function found in the program.")
    )
    if (mainFunc.params.nonEmpty) {
      throw TypeError(
        "'main' function cannot have parameters.",
        Some(mainFunc.loc)
      )
    }

    // Check all function bodies
    program.functions.foreach(checkFunction)
  }

  /** Checks a single function definition. */
  private def checkFunction(func: FuncDef): Unit = {
    val localContext: LocalContext = mutable.Map.empty

    // Populate context with function parameters
    func.params.foreach { param =>
      validateType(param.typ)
      val isMutable = param.mode == ParamMode.Inout
      localContext(param.name) =
        VarInfo(param.typ, VarState.Owned, isMutable, param.loc)
    }

    // Check the function body, providing the expected return type
    checkStatement(func.body, localContext, Some(func.returnType))
  }

  /** Recursively checks a statement. */
  private def checkStatement(
      stmt: Statement,
      context: LocalContext,
      expectedReturnType: Option[Type]
  ): Unit = stmt match {
    case BlockStatement(statements, _) =>
      // Each block gets a copy of the context to handle shadowing and scope.
      val blockContext = context.clone()
      statements.foreach(s =>
        checkStatement(s, blockContext, expectedReturnType)
      )

    case LetStatement(varName, typeAnnotation, init, loc) =>
      if (context.contains(varName)) {
        throw TypeError(
          s"Variable '$varName' is already defined in this scope.",
          Some(loc)
        )
      }
      val initType = checkExpression(init, context)

      typeAnnotation.foreach { declaredType =>
        validateType(declaredType)
        if (!areTypesEqual(declaredType, initType)) {
          throw TypeError(
            s"Type mismatch for '$varName'. Expected ${typeToString(declaredType)} but got ${typeToString(initType)}.",
            Some(init.loc)
          )
        }
      }

      val finalType = typeAnnotation.getOrElse(initType)

      // Move the value from the initializer if it's not a copy type
      if (!isCopyType(finalType)) {
        handleMove(init, context)
      }

      // Add the new variable to the context. 'let' bindings are immutable.
      context(varName) =
        VarInfo(finalType, VarState.Owned, isMutable = false, loc)

    case ExpressionStatement(expr, _) =>
      checkExpression(
        expr,
        context
      ) // Result is discarded, but ownership effects are applied.

    case AssignmentStatement(target, value, loc) =>
      // Check that the target is a valid, mutable place
      val targetType =
        checkPlaceExpression(target, context, requireMutable = true)
      val valueType = checkExpression(value, context)

      if (!areTypesEqual(targetType, valueType)) {
        throw TypeError(
          s"Cannot assign value of type ${typeToString(valueType)} to target of type ${typeToString(targetType)}.",
          Some(value.loc)
        )
      }

      // Move the value if it's not a copy type
      if (!isCopyType(valueType)) {
        handleMove(value, context)
      }

    case ReturnStatement(exprOpt, loc) =>
      val returnType =
        exprOpt.map(checkExpression(_, context)).getOrElse(UnitType())
      val expected = expectedReturnType.getOrElse(
        throw TypeError(
          "Return statement used outside of a function.",
          Some(loc)
        )
      )
      if (!areTypesEqual(returnType, expected)) {
        throw TypeError(
          s"Return type mismatch. Expected ${typeToString(expected)} but got ${typeToString(returnType)}.",
          exprOpt.map(_.loc).orElse(Some(loc))
        )
      }

      // Handle moving the return value
      exprOpt.foreach { expr =>
        if (!isCopyType(returnType)) {
          handleMove(expr, context)
        }
      }
  }

  /** Recursively checks an expression and returns its type. */
  private def checkExpression(
      expr: Expression,
      context: LocalContext
  ): Type = expr match {
    case IntLiteral(_, _)  => IntType()
    case BoolLiteral(_, _) => BoolType()

    case Variable(name, loc) =>
      val varInfo = context.getOrElse(
        name,
        throw TypeError(s"Variable '$name' not found in this scope.", Some(loc))
      )
      varInfo.state match {
        case VarState.Moved =>
          throw TypeError(s"Use of moved value '$name'.", Some(loc))
        case _ =>
          varInfo.typ // OK to read from Owned, BorrowedRead, BorrowedWrite
      }

    case StructLiteral(typeName, values, loc) =>
      checkStructLiteral(typeName, values, context, loc)
      StructNameType(typeName)

    case ManagedStructLiteral(typeName, values, loc) =>
      checkStructLiteral(typeName, values, context, loc)
      ManagedType(StructNameType(typeName))

    case FieldAccess(obj, fieldName, loc) =>
      val objType = checkExpression(obj, context)
      objType match {
        case StructNameType(structName, _) =>
          val structDef = globalContext.structs.getOrElse(
            structName,
            throw new IllegalStateException(
              s"Struct definition for '$structName' not found in global context."
            )
          )
          structDef.fields.find(_._1 == fieldName) match {
            case Some((_, fieldType)) => fieldType
            case None =>
              throw TypeError(
                s"Struct '$structName' has no field named '$fieldName'.",
                Some(loc)
              )
          }
        case _ =>
          throw TypeError(
            s"Field access is only allowed on structs. Found type ${typeToString(objType)}.",
            Some(obj.loc)
          )
      }

    case FunctionCall(funcExpr, args, loc) =>
      val funcName = funcExpr match {
        case Variable(name, _) => name
        case _ =>
          throw TypeError(
            "Dynamic function calls are not supported.",
            Some(funcExpr.loc)
          )
      }

      val funcDef = globalContext.functions.getOrElse(
        funcName,
        throw TypeError(s"Function '$funcName' not found.", Some(funcExpr.loc))
      )

      if (args.length != funcDef.params.length) {
        throw TypeError(
          s"Function '$funcName' expects ${funcDef.params.length} arguments, but ${args.length} were provided.",
          Some(loc)
        )
      }

      // Check each argument against its parameter
      args.zip(funcDef.params).foreach { case (argExpr, param) =>
        val argType = checkExpression(argExpr, context)
        if (!areTypesEqual(argType, param.typ)) {
          throw TypeError(
            s"Type mismatch for argument to parameter '${param.name}'. Expected ${typeToString(
                param.typ
              )} but got ${typeToString(argType)}.",
            Some(argExpr.loc)
          )
        }

        // Ownership checks based on parameter mode
        param.mode match {
          case ParamMode.Move =>
            if (!isCopyType(argType)) {
              handleMove(argExpr, context)
            }
          case ParamMode.Ref =>
            checkBorrow(argExpr, context, isMutableBorrow = false)
          case ParamMode.Inout =>
            checkBorrow(argExpr, context, isMutableBorrow = true)
        }
      }

      funcDef.returnType
  }

  /** Checks if an expression is a valid "place" (l-value) for assignment or
    * mutable borrowing.
    */
  private def checkPlaceExpression(
      expr: Expression,
      context: LocalContext,
      requireMutable: Boolean
  ): Type = expr match {
    case Variable(name, loc) =>
      val varInfo = context.getOrElse(
        name,
        throw TypeError(s"Variable '$name' not found.", Some(loc))
      )
      if (requireMutable && !varInfo.isMutable) {
        throw TypeError(
          s"Cannot assign to immutable variable '$name'. Note: only 'inout' parameters are mutable.",
          Some(loc)
        )
      }
      varInfo.state match {
        case VarState.Moved =>
          throw TypeError(
            s"Cannot use '$name' as it has been moved.",
            Some(loc)
          )
        case _ => varInfo.typ
      }

    case FieldAccess(obj, fieldName, loc) =>
      // To modify a field, the container must be a mutable place.
      val objType = checkPlaceExpression(obj, context, requireMutable)
      objType match {
        case StructNameType(structName, _) =>
          val structDef = globalContext.structs.getOrElse(
            structName,
            throw new IllegalStateException("Struct disappeared")
          )
          structDef.fields.find(_._1 == fieldName) match {
            case Some((_, fieldType)) => fieldType
            case None =>
              throw TypeError(
                s"Struct '$structName' has no field named '$fieldName'.",
                Some(loc)
              )
          }
        case _ =>
          throw TypeError(
            s"Field access is only allowed on structs. Found type ${typeToString(objType)}.",
            Some(obj.loc)
          )
      }

    case _ =>
      throw TypeError(
        "Expression is not a valid assignment target.",
        Some(expr.loc)
      )
  }

  /** Marks a variable as moved, enforcing ownership rules. */
  private def handleMove(
      sourceExpr: Expression,
      context: LocalContext
  ): Unit = {
    sourceExpr match {
      case Variable(name, loc) =>
        val varInfo = context.getOrElse(
          name,
          throw new IllegalStateException(
            "Variable disappeared during move check"
          )
        )
        varInfo.state match {
          case VarState.Owned =>
            context(name) = varInfo.copy(state = VarState.Moved)
          case VarState.Moved =>
            throw TypeError(
              s"Cannot move from '$name' because it was already moved.",
              Some(loc)
            )
          case VarState.BorrowedRead =>
            throw TypeError(
              s"Cannot move from '$name' because it is immutably borrowed.",
              Some(loc)
            )
          case VarState.BorrowedWrite =>
            throw TypeError(
              s"Cannot move from '$name' because it is mutably borrowed.",
              Some(loc)
            )
        }
      case _ =>
      // Moving from a temporary (e.g. `let x = Point{...}`) is fine.
      // Moving from a field access is not supported in this simplified model.
    }
  }

  /** Checks if a borrow is valid based on the variable's current state. */
  private def checkBorrow(
      argExpr: Expression,
      context: LocalContext,
      isMutableBorrow: Boolean
  ): Unit = {
    argExpr match {
      case Variable(name, loc) =>
        val varInfo = context.getOrElse(
          name,
          throw new IllegalStateException(
            "Variable disappeared during borrow check"
          )
        )

        if (isMutableBorrow) { // 'inout' parameter
          varInfo.state match {
            case VarState.Owned => // OK
            case VarState.Moved =>
              throw TypeError(
                s"Cannot mutably borrow '$name' as it has been moved.",
                Some(loc)
              )
            case VarState.BorrowedRead =>
              throw TypeError(
                s"Cannot mutably borrow '$name' as it is already immutably borrowed.",
                Some(loc)
              )
            case VarState.BorrowedWrite =>
              throw TypeError(
                s"Cannot mutably borrow '$name' as it is already mutably borrowed.",
                Some(loc)
              )
          }
        } else { // 'ref' parameter
          varInfo.state match {
            case VarState.Owned | VarState.BorrowedRead => // OK
            case VarState.Moved =>
              throw TypeError(
                s"Cannot borrow '$name' as it has been moved.",
                Some(loc)
              )
            case VarState.BorrowedWrite =>
              throw TypeError(
                s"Cannot immutably borrow '$name' as it is already mutably borrowed.",
                Some(loc)
              )
          }
        }
      case _ =>
        throw TypeError(
          "Cannot borrow from a temporary value.",
          Some(argExpr.loc)
        )
    }
  }

  /** Helper to check struct literal fields and types. */
  private def checkStructLiteral(
      typeName: String,
      values: List[(String, Expression)],
      context: LocalContext,
      loc: SourceLocation
  ): Unit = {
    val structDef = globalContext.structs.getOrElse(
      typeName,
      throw TypeError(s"Unknown struct '$typeName'.", Some(loc))
    )

    val providedFields = values.map(_._1).toSet
    val expectedFields = structDef.fields.map(_._1).toSet
    if (providedFields != expectedFields) {
      throw TypeError(
        s"Struct '$typeName' initialization has incorrect fields. Expected: ${expectedFields
            .mkString(", ")}, Got: ${providedFields.mkString(", ")}.",
        Some(loc)
      )
    }

    values.foreach { case (fieldName, fieldExpr) =>
      val fieldInitType = checkExpression(fieldExpr, context)
      val expectedFieldType = structDef.fields.find(_._1 == fieldName).get._2
      if (!areTypesEqual(fieldInitType, expectedFieldType)) {
        throw TypeError(
          s"Type mismatch for field '$fieldName' in '$typeName' initialization. Expected ${typeToString(expectedFieldType)} but got ${typeToString(fieldInitType)}.",
          Some(fieldExpr.loc)
        )
      }
      if (!isCopyType(fieldInitType)) {
        handleMove(fieldExpr, context)
      }
    }
  }

  /** Checks if a type name exists in the global context. */
  private def validateType(t: Type): Unit = t match {
    case StructNameType(name, loc) =>
      if (
        !globalContext.structs.contains(name) && !globalContext.resources
          .contains(name)
      ) {
        throw TypeError(s"Unknown type '$name'.", loc)
      }
    case ManagedType(inner, _) => validateType(inner)
    case _                     => // Primitive types are always valid
  }

  /** Converts a Type AST to a string for error messages. */
  private def typeToString(t: Type): String = t match {
    case IntType(_)              => "int"
    case BoolType(_)             => "bool"
    case UnitType(_)             => "unit"
    case StructNameType(name, _) => name
    case ManagedType(inner, _)   => s"managed ${typeToString(inner)}"
  }

  /** Checks for type equality, ignoring source locations. */
  private def areTypesEqual(t1: Type, t2: Type): Boolean = (t1, t2) match {
    case (IntType(_), IntType(_))                       => true
    case (BoolType(_), BoolType(_))                     => true
    case (UnitType(_), UnitType(_))                     => true
    case (StructNameType(n1, _), StructNameType(n2, _)) => n1 == n2
    case (ManagedType(it1, _), ManagedType(it2, _)) => areTypesEqual(it1, it2)
    case _                                          => false
  }

  /** Determines if a type is a simple "Copy" type (like primitives). */
  private def isCopyType(t: Type): Boolean = t match {
    case IntType(_) | BoolType(_) | UnitType(_) => true
    case _ => false // Structs, Resources, Managed types are Move types
  }
}
