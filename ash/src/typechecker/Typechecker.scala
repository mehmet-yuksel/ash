package ash.typechecker

import ash.parser._
import ash.parser.ErrorUtils
import ash.typechecker.typed._

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

class Typechecker(program: Program, input: String) {

  // Helper method to create TypeError with preview
  private def createTypeError(
      message: String,
      loc: Option[SourceLocation] = None
  ): TypeError = {
    loc match {
      case Some(location) =>
        val preview = ErrorUtils.generateErrorPreview(input, location)
        TypeError(s"$message\n$preview", loc)
      case None =>
        TypeError(message, loc)
    }
  }

  // Build the global context from top-level definitions
  private val globalContext: GlobalContext = {
    // Check for duplicate struct names
    val duplicateStruct = program.structs.groupBy(_.name).find(_._2.size > 1)
    duplicateStruct.foreach { case (name, defs) =>
      throw createTypeError(
        s"Struct '$name' is defined multiple times.",
        Some(defs(1).loc)
      )
    }

    // Check for duplicate resource names
    val duplicateResource =
      program.resources.groupBy(_.name).find(_._2.size > 1)
    duplicateResource.foreach { case (name, defs) =>
      throw createTypeError(
        s"Resource '$name' is defined multiple times.",
        Some(defs(1).loc)
      )
    }

    // Check for duplicate function names
    val duplicateFunction =
      program.functions.groupBy(_.name).find(_._2.size > 1)
    duplicateFunction.foreach { case (name, defs) =>
      throw createTypeError(
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
  def check(): TypedProgram = {
    val mainFunc = globalContext.functions.getOrElse(
      "main",
      throw createTypeError("No 'main' function found in the program.")
    )
    if (mainFunc.params.nonEmpty) {
      throw createTypeError(
        "'main' function cannot have parameters.",
        Some(mainFunc.loc)
      )
    }

    // Check all resource cleanup blocks
    program.resources.foreach(checkResourceCleanup)

    // Check all function bodies and collect the typed versions
    val typedFunctions = program.functions.map(checkFunction)
    val typedResources = program.resources.map(checkResource)

    TypedProgram(
      program.structs,
      typedResources,
      typedFunctions,
      program.loc
    )
  }

  /** Checks a resource's cleanup block. */
  private def checkResourceCleanup(resource: ResourceDef): Unit = {
    resource.cleanup.foreach { cleanupBlock =>
      val localContext: LocalContext = mutable.Map.empty

      // Add all resource fields to the cleanup context as mutable owned variables
      resource.fields.foreach { case (fieldName, fieldType) =>
        validateType(fieldType)
        localContext(fieldName) = VarInfo(
          fieldType,
          VarState.Owned,
          isMutable = true, // All fields are mutable in cleanup
          resource.loc
        )
      }

      // Check the cleanup block with unit return type expected
      checkStatement(cleanupBlock, localContext, Some(UnitType()))
    }
  }

  /** Converts a ResourceDef to TypedResourceDef. */
  private def checkResource(resource: ResourceDef): TypedResourceDef = {
    val typedCleanup = resource.cleanup.map { cleanupBlock =>
      val localContext: LocalContext = mutable.Map.empty

      // Add all resource fields to the cleanup context as mutable owned variables
      resource.fields.foreach { case (fieldName, fieldType) =>
        validateType(fieldType)
        localContext(fieldName) = VarInfo(
          fieldType,
          VarState.Owned,
          isMutable = true, // All fields are mutable in cleanup
          resource.loc
        )
      }

      // Check and convert the cleanup block
      checkStatement(cleanupBlock, localContext, Some(UnitType())) match {
        case block: TypedBlockStatement => block
        case other => TypedBlockStatement(List(other), cleanupBlock.loc)
      }
    }

    TypedResourceDef(
      resource.name,
      resource.fields,
      typedCleanup,
      resource.loc
    )
  }

  /** Checks a single function definition. */
  private def checkFunction(func: FuncDef): TypedFuncDef = {
    val localContext: LocalContext = mutable.Map.empty

    // Populate context with function parameters
    func.params.foreach { param =>
      validateType(param.typ)
      val (isMutable, initialState) = param.mode match {
        case ParamMode.Move(mutable) => (mutable, VarState.Owned)
        case ParamMode.Inout         => (true, VarState.BorrowedWrite)
        case ParamMode.Ref           => (false, VarState.BorrowedRead)
      }
      localContext(param.name) =
        VarInfo(param.typ, initialState, isMutable, param.loc)
    }

    // Check the function body, providing the expected return type
    val typedBody =
      checkStatement(func.body, localContext, Some(func.returnType)) match {
        case tb: TypedBlockStatement => tb
        case other =>
          throw new IllegalStateException(
            s"Function body must be a block, but got ${other.getClass}"
          )
      }

    TypedFuncDef(func.name, func.params, func.returnType, typedBody, func.loc)
  }

  /** Recursively checks a statement. */
  private def checkStatement(
      stmt: Statement,
      context: LocalContext,
      expectedReturnType: Option[Type]
  ): TypedStatement = stmt match {
    case BlockStatement(statements, loc) =>
      // Each block gets a copy of the context to handle shadowing and scope.
      val blockContext = context.clone()
      val typedStatements =
        statements.map(s => checkStatement(s, blockContext, expectedReturnType))
      TypedBlockStatement(typedStatements, loc)

    case LetStatement(varName, isMutable, typeAnnotation, init, loc) =>
      if (context.contains(varName)) {
        throw createTypeError(
          s"Variable '$varName' is already defined in this scope.",
          Some(loc)
        )
      }
      val typedInit = checkExpression(init, context)

      typeAnnotation.foreach { declaredType =>
        validateType(declaredType)
        if (!areTypesEqual(declaredType, typedInit.typ)) {
          throw createTypeError(
            s"Type mismatch for '$varName'. Expected ${typeToString(declaredType)} but got ${typeToString(typedInit.typ)}.",
            Some(init.loc)
          )
        }
      }

      val finalType = typeAnnotation.getOrElse(typedInit.typ)

      // Move the value from the initializer if it's not a copy type
      if (!isCopyType(finalType)) {
        handleMove(init, context)
      }

      // Add the new variable to the context with mutability from the AST
      context(varName) = VarInfo(finalType, VarState.Owned, isMutable, loc)
      TypedLetStatement(varName, isMutable, typedInit, loc)

    case ExpressionStatement(expr, loc) =>
      val typedExpr = checkExpression(expr, context)
      TypedExpressionStatement(typedExpr, loc)

    case AssignmentStatement(target, value, loc) =>
      val typedValue = checkExpression(value, context)
      // Pass the typed value to checkPlaceExpression to avoid re-checking
      val typedTarget =
        checkPlaceExpression(target, context, requireMutable = true)

      if (!areTypesEqual(typedTarget.typ, typedValue.typ)) {
        throw createTypeError(
          s"Cannot assign value of type ${typeToString(typedValue.typ)} to target of type ${typeToString(typedTarget.typ)}.",
          Some(value.loc)
        )
      }

      // Move the value if it's not a copy type
      if (!isCopyType(typedValue.typ)) {
        handleMove(value, context)
      }
      TypedAssignmentStatement(typedTarget, typedValue, loc)

    case ReturnStatement(exprOpt, loc) =>
      val typedExprOpt = exprOpt.map(checkExpression(_, context))
      val returnType =
        typedExprOpt.map(_.typ).getOrElse(UnitType())
      val expected = expectedReturnType.getOrElse(
        throw createTypeError(
          "Return statement used outside of a function.",
          Some(loc)
        )
      )
      if (!areTypesEqual(returnType, expected)) {
        throw createTypeError(
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
      TypedReturnStatement(typedExprOpt, loc)
  }

  /** Recursively checks an expression and returns its typed version. */
  private def checkExpression(
      expr: Expression,
      context: LocalContext,
      isManagedContext: Boolean = false
  ): TypedExpression = expr match {
    case IntLiteral(v, loc)  => TypedIntLiteral(v, IntType(), loc)
    case BoolLiteral(v, loc) => TypedBoolLiteral(v, BoolType(), loc)

    case Variable(name, loc) =>
      val varInfo = context.getOrElse(
        name,
        throw createTypeError(
          s"Variable '$name' not found in this scope.",
          Some(loc)
        )
      )
      varInfo.state match {
        case VarState.Moved =>
          throw createTypeError(s"Use of moved value '$name'.", Some(loc))
        case _ => // OK to read from Owned, BorrowedRead, BorrowedWrite
          TypedVariable(name, varInfo.typ, loc)
      }

    case StructLiteral(typeName, values, loc) =>
      if (isManagedContext) {
        val (typedValues, structType, isResource) =
          checkStructLiteral(
            typeName,
            values,
            context,
            loc,
            isManagedContext = true
          )
        if (isResource) {
          throw createTypeError(
            s"Resource '$typeName' cannot be allocated as managed.",
            Some(loc)
          )
        }
        TypedManagedStructLiteral(
          typeName,
          typedValues,
          ManagedType(structType),
          loc
        )
      } else {
        val (typedValues, structType, _) =
          checkStructLiteral(
            typeName,
            values,
            context,
            loc,
            isManagedContext = false
          )
        TypedStructLiteral(typeName, typedValues, structType, loc)
      }

    case ManagedStructLiteral(typeName, values, loc) =>
      val (typedValues, structType, isResource) =
        checkStructLiteral(
          typeName,
          values,
          context,
          loc,
          isManagedContext = true
        )
      if (isResource) {
        throw createTypeError(
          s"Resource '$typeName' cannot be allocated as managed.",
          Some(loc)
        )
      }
      TypedManagedStructLiteral(
        typeName,
        typedValues,
        ManagedType(structType),
        loc
      )

    case FieldAccess(obj, fieldName, loc) =>
      val typedObj = checkExpression(obj, context)
      val (baseType, isObjManaged) = typedObj.typ match {
        case st @ StructNameType(_, _)             => (st, false)
        case ManagedType(inner: StructNameType, _) => (inner, true)
        case ManagedType(inner, _) =>
          throw createTypeError(
            s"Field access on managed type is only allowed for structs. Found ${typeToString(ManagedType(inner))}",
            Some(obj.loc)
          )
        case _ =>
          throw createTypeError(
            s"Field access is only allowed on structs and resources. Found type ${typeToString(typedObj.typ)}.",
            Some(obj.loc)
          )
      }

      val structName = baseType.name
      val rawFieldType = getFieldType(structName, fieldName, loc)

      val finalFieldType =
        if (isObjManaged && isStructOrResourceType(rawFieldType)) {
          ManagedType(rawFieldType)
        } else {
          rawFieldType
        }
      TypedFieldAccess(typedObj, fieldName, finalFieldType, loc)

    case FunctionCall(funcExpr, args, loc) =>
      val funcName = funcExpr match {
        case Variable(name, _) => name
        case _ =>
          throw createTypeError(
            "Dynamic function calls are not supported.",
            Some(funcExpr.loc)
          )
      }

      val funcDef = globalContext.functions.getOrElse(
        funcName,
        throw createTypeError(
          s"Function '$funcName' not found.",
          Some(funcExpr.loc)
        )
      )

      if (args.length != funcDef.params.length) {
        throw createTypeError(
          s"Function '$funcName' expects ${funcDef.params.length} arguments, but ${args.length} were provided.",
          Some(loc)
        )
      }

      // Check each argument against its parameter
      val typedArgs = args.zip(funcDef.params).map { case (argExpr, param) =>
        val typedArg = checkExpression(argExpr, context)
        if (!areTypesEqual(typedArg.typ, param.typ)) {
          throw createTypeError(
            s"Type mismatch for argument to parameter '${param.name}'. Expected ${typeToString(
                param.typ
              )} but got ${typeToString(typedArg.typ)}.",
            Some(argExpr.loc)
          )
        }

        // Ownership checks based on parameter mode
        param.mode match {
          case ParamMode.Move(_) =>
            if (!isCopyType(typedArg.typ)) {
              handleMove(argExpr, context)
            }
          case ParamMode.Ref =>
            checkBorrow(argExpr, context, isMutableBorrow = false)
          case ParamMode.Inout =>
            checkBorrow(argExpr, context, isMutableBorrow = true)
        }
        typedArg
      }

      TypedFunctionCall(funcName, typedArgs, funcDef.returnType, loc)

    case PrintlnExpression(formatString, args, loc) =>
      // Type check all arguments - they can be any type
      val typedArgs = args.map(checkExpression(_, context))
      // println! always returns unit
      TypedPrintlnExpression(formatString, typedArgs, UnitType(), loc)

    case BinaryExpression(left, op, right, loc) =>
      val typedLeft = checkExpression(left, context)
      val typedRight = checkExpression(right, context)

      val resultType = op match {
        case BinaryOp.Add | BinaryOp.Sub =>
          // Arithmetic operators require both operands to be int and return int
          if (
            !areTypesEqual(typedLeft.typ, IntType()) || !areTypesEqual(
              typedRight.typ,
              IntType()
            )
          ) {
            throw createTypeError(
              s"Arithmetic operator ${binaryOpToString(op)} requires both operands to be int, but got ${typeToString(
                  typedLeft.typ
                )} and ${typeToString(typedRight.typ)}.",
              Some(loc)
            )
          }
          IntType()

        case BinaryOp.Lt | BinaryOp.Le | BinaryOp.Gt | BinaryOp.Ge =>
          // Comparison operators require both operands to be int and return bool
          if (
            !areTypesEqual(typedLeft.typ, IntType()) || !areTypesEqual(
              typedRight.typ,
              IntType()
            )
          ) {
            throw createTypeError(
              s"Comparison operator ${binaryOpToString(op)} requires both operands to be int, but got ${typeToString(
                  typedLeft.typ
                )} and ${typeToString(typedRight.typ)}.",
              Some(loc)
            )
          }
          BoolType()

        case BinaryOp.Eq | BinaryOp.Ne =>
          // Equality operators require both operands to be the same type and return bool
          if (!areTypesEqual(typedLeft.typ, typedRight.typ)) {
            throw createTypeError(
              s"Equality operator ${binaryOpToString(op)} requires both operands to be the same type, but got ${typeToString(
                  typedLeft.typ
                )} and ${typeToString(typedRight.typ)}.",
              Some(loc)
            )
          }
          // Only allow equality on copy types for simplicity
          if (!isCopyType(typedLeft.typ)) {
            throw createTypeError(
              s"Equality operator ${binaryOpToString(op)} is only supported for copy types (int, bool, unit), but got ${typeToString(typedLeft.typ)}.",
              Some(loc)
            )
          }
          BoolType()
      }

      TypedBinaryExpression(typedLeft, op, typedRight, resultType, loc)
  }

  /** Checks if an expression is a valid "place" (l-value) for assignment or
    * mutable borrowing.
    */
  private def checkPlaceExpression(
      expr: Expression,
      context: LocalContext,
      requireMutable: Boolean
  ): TypedExpression = expr match {
    case Variable(name, loc) =>
      val varInfo = context.getOrElse(
        name,
        throw createTypeError(s"Variable '$name' not found.", Some(loc))
      )
      if (requireMutable && !varInfo.isMutable) {
        throw createTypeError(
          s"Cannot assign to immutable variable '$name'. Use 'let mut' to declare mutable variables or 'inout' for mutable parameters.",
          Some(loc)
        )
      }
      varInfo.state match {
        case VarState.Moved =>
          throw createTypeError(
            s"Cannot use '$name' as it has been moved.",
            Some(loc)
          )
        case _ => TypedVariable(name, varInfo.typ, loc)
      }

    case FieldAccess(obj, fieldName, loc) =>
      // To modify a field, the container must be a mutable place.
      val typedObj = checkPlaceExpression(obj, context, requireMutable)
      val (baseType, isObjManaged) = typedObj.typ match {
        case st @ StructNameType(_, _)             => (st, false)
        case ManagedType(inner: StructNameType, _) => (inner, true)
        case ManagedType(inner, _) =>
          throw createTypeError(
            s"Field access on managed type is only allowed for structs. Found ${typeToString(ManagedType(inner))}",
            Some(obj.loc)
          )
        case _ =>
          throw createTypeError(
            s"Field access is only allowed on structs and resources. Found type ${typeToString(typedObj.typ)}.",
            Some(obj.loc)
          )
      }
      val structName = baseType.name
      val rawFieldType = getFieldType(structName, fieldName, loc)

      val finalFieldType =
        if (isObjManaged && isStructOrResourceType(rawFieldType)) {
          ManagedType(rawFieldType)
        } else {
          rawFieldType
        }
      TypedFieldAccess(typedObj, fieldName, finalFieldType, loc)

    case _ =>
      throw createTypeError(
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
            throw createTypeError(
              s"Cannot move from '$name' because it was already moved.",
              Some(loc)
            )
          case VarState.BorrowedRead =>
            throw createTypeError(
              s"Cannot move from '$name' because it is immutably borrowed.",
              Some(loc)
            )
          case VarState.BorrowedWrite =>
            throw createTypeError(
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
    // We only need to check borrows of variables. Borrowing a temporary is an error.
    // Borrowing a field is allowed if the base variable is accessible.
    argExpr match {
      case Variable(name, loc) =>
        val varInfo = context.getOrElse(
          name,
          throw new IllegalStateException(
            "Variable disappeared during borrow check"
          )
        )

        if (isMutableBorrow) { // 'inout' parameter
          if (!varInfo.isMutable) {
            throw createTypeError(
              s"Cannot mutably borrow immutable variable '$name'. Mark it as 'mut' or pass it to an 'inout' parameter.",
              Some(loc)
            )
          }
          varInfo.state match {
            case VarState.Owned => // OK
            case VarState.Moved =>
              throw createTypeError(
                s"Cannot mutably borrow '$name' as it has been moved.",
                Some(loc)
              )
            case VarState.BorrowedRead =>
              throw createTypeError(
                s"Cannot mutably borrow '$name' as it is already immutably borrowed.",
                Some(loc)
              )
            case VarState.BorrowedWrite =>
              throw createTypeError(
                s"Cannot mutably borrow '$name' as it is already mutably borrowed.",
                Some(loc)
              )
          }
        } else { // 'ref' parameter
          varInfo.state match {
            case VarState.Owned | VarState.BorrowedRead => // OK
            case VarState.Moved =>
              throw createTypeError(
                s"Cannot borrow '$name' as it has been moved.",
                Some(loc)
              )
            case VarState.BorrowedWrite =>
              throw createTypeError(
                s"Cannot immutably borrow '$name' as it is already mutably borrowed.",
                Some(loc)
              )
          }
        }
      case FieldAccess(obj, _, loc) =>
        // Recursively check if the base of the field access can be borrowed.
        // This is a simplification; a real borrow checker would track borrows per-field.
        checkBorrow(obj, context, isMutableBorrow)
      case _ =>
        throw createTypeError(
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
      loc: SourceLocation,
      isManagedContext: Boolean
  ): (List[(String, TypedExpression)], StructNameType, Boolean) = {
    val definition: Either[StructDef, ResourceDef] =
      globalContext.structs
        .get(typeName)
        .map(Left(_))
        .orElse(globalContext.resources.get(typeName).map(Right(_)))
        .getOrElse(
          throw createTypeError(
            s"Unknown struct or resource '$typeName'.",
            Some(loc)
          )
        )

    val (expectedFieldsList, isResource) = definition match {
      case Left(structDef)    => (structDef.fields, false)
      case Right(resourceDef) => (resourceDef.fields, true)
    }

    val providedFields = values.map(_._1).toSet
    val expectedFields = expectedFieldsList.map(_._1).toSet
    if (providedFields != expectedFields) {
      throw createTypeError(
        s"'$typeName' initialization has incorrect fields. Expected: ${expectedFields
            .mkString(", ")}, Got: ${providedFields.mkString(", ")}.",
        Some(loc)
      )
    }

    val typedValues = values.map { case (fieldName, fieldExpr) =>
      val typedFieldExpr = checkExpression(fieldExpr, context, isManagedContext)
      val expectedFieldType = expectedFieldsList.find(_._1 == fieldName).get._2

      val finalExpectedType =
        if (isManagedContext && isStructOrResourceType(expectedFieldType)) {
          ManagedType(expectedFieldType)
        } else {
          expectedFieldType
        }

      if (!areTypesEqual(typedFieldExpr.typ, finalExpectedType)) {
        throw createTypeError(
          s"Type mismatch for field '$fieldName' in '$typeName' initialization. Expected ${typeToString(
              finalExpectedType
            )} but got ${typeToString(typedFieldExpr.typ)}.",
          Some(fieldExpr.loc)
        )
      }
      if (!isCopyType(typedFieldExpr.typ)) {
        handleMove(fieldExpr, context)
      }
      (fieldName, typedFieldExpr)
    }
    (typedValues, StructNameType(typeName), isResource)
  }

  private def getFieldType(
      structName: String,
      fieldName: String,
      loc: SourceLocation
  ): Type = {
    val definition: Either[StructDef, ResourceDef] =
      globalContext.structs
        .get(structName)
        .map(Left(_))
        .orElse(globalContext.resources.get(structName).map(Right(_)))
        .getOrElse(
          // This should not happen if validateType is called correctly
          throw new IllegalStateException(
            s"Definition for '$structName' not found in global context."
          )
        )
    val fields = definition match {
      case Left(s)  => s.fields
      case Right(r) => r.fields
    }
    fields.find(_._1 == fieldName) match {
      case Some((_, fieldType)) => fieldType
      case None =>
        throw createTypeError(
          s"Type '$structName' has no field named '$fieldName'.",
          Some(loc)
        )
    }
  }

  /** Helper to check if a type is a struct or resource. */
  private def isStructOrResourceType(t: Type): Boolean = t match {
    case StructNameType(name, _) =>
      globalContext.structs.contains(name) || globalContext.resources.contains(
        name
      )
    case _ => false
  }

  /** Checks if a type name exists in the global context. */
  private def validateType(t: Type): Unit = t match {
    case StructNameType(name, loc) =>
      if (
        !globalContext.structs.contains(name) && !globalContext.resources
          .contains(name)
      ) {
        throw createTypeError(s"Unknown type '$name'.", loc)
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
    case ManagedType(_, _) =>
      true // Managed types are handles that can be copied
    case _ => false // Structs, Resources are Move types
  }

  /** Converts a BinaryOp to a string for error messages. */
  private def binaryOpToString(op: BinaryOp): String = op match {
    case BinaryOp.Add => "+"
    case BinaryOp.Sub => "-"
    case BinaryOp.Lt  => "<"
    case BinaryOp.Le  => "<="
    case BinaryOp.Gt  => ">"
    case BinaryOp.Ge  => ">="
    case BinaryOp.Eq  => "=="
    case BinaryOp.Ne  => "!="
  }
}
