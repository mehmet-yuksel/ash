package ash.typechecker

import ash.parser._
import ash.parser.ErrorUtils
import ash.typechecker.typed._

import scala.collection.mutable

case class TypeError(message: String, loc: Option[SourceLocation] = None)
    extends Exception(message)

class Typechecker(program: Program, input: String) {

  private def createTypeError(message: String, loc: Option[SourceLocation] = None): TypeError =
    loc match {
      case Some(l) =>
        val preview = ErrorUtils.generateErrorPreview(input, l)
        TypeError(s"$message\n$preview", loc)
      case None => TypeError(message, loc)
    }

  enum VarState:
    case Owned, Moved, BorrowedRead, BorrowedWrite

  enum VarAction:
    case Move, Read, BorrowRead, BorrowWrite

  case class VarInfo(
      typ: Type,
      state: VarState,
      isMutable: Boolean,
      definitionLoc: SourceLocation
  )

  class LocalScope(private val underlying: mutable.Map[String, VarInfo] = mutable.Map.empty) {
    def contains(name: String): Boolean = underlying.contains(name)
    def get(name: String): Option[VarInfo] = underlying.get(name)
    def declare(name: String, info: VarInfo): Unit =
      if (underlying.contains(name))
        throw createTypeError(s"Variable '$name' is already defined in this scope.", Some(info.definitionLoc))
      else underlying(name) = info

    def update(name: String, info: VarInfo): Unit =
      if (!underlying.contains(name))
        throw new IllegalStateException(s"Attempt to update unknown variable '$name'")
      else underlying(name) = info

    def cloneScope(): LocalScope = new LocalScope(underlying.clone())
  }

  case class GlobalContext(
      structs: Map[String, StructDef],
      resources: Map[String, ResourceDef],
      functions: Map[String, FuncDef]
  )

  private val globalContext: GlobalContext = {
    def dupCheck[T](items: Seq[(String, T)], kind: String): Unit = {
      val dups = items.groupMap(_._1)(_._2).filter(_._2.size > 1)
      dups.headOption.foreach { case (name, defs) =>
        throw createTypeError(s"$kind '$name' is defined multiple times.", None)
      }
    }

    dupCheck(program.structs.map(s => s.name -> s), "Struct")
    dupCheck(program.resources.map(r => r.name -> r), "Resource")
    dupCheck(program.functions.map(f => f.name -> f), "Function")

    GlobalContext(
      program.structs.map(s => s.name -> s).toMap,
      program.resources.map(r => r.name -> r).toMap,
      program.functions.map(f => f.name -> f).toMap
    )
  }

  private def transition(state: VarState, action: VarAction): Either[String, VarState] =
    (state, action) match {
      case (VarState.Owned, VarAction.Move)      => Right(VarState.Moved)
      case (VarState.Owned, VarAction.Read)      => Right(VarState.Owned)
      case (VarState.Owned, VarAction.BorrowRead)=> Right(VarState.Owned)
      case (VarState.Owned, VarAction.BorrowWrite)=> Right(VarState.Owned)

      case (VarState.Moved, VarAction.Read)      => Left("Use of moved value")
      case (VarState.Moved, VarAction.Move)      => Left("Cannot move from already moved value")
      case (VarState.Moved, VarAction.BorrowRead)=> Left("Cannot borrow moved value")
      case (VarState.Moved, VarAction.BorrowWrite)=> Left("Cannot mutably borrow moved value")

      case (VarState.BorrowedRead, VarAction.Read) => Right(VarState.BorrowedRead)
      case (VarState.BorrowedRead, VarAction.BorrowRead) => Right(VarState.BorrowedRead)
      case (VarState.BorrowedRead, VarAction.Move) => Left("Cannot move from immutably borrowed value")
      case (VarState.BorrowedRead, VarAction.BorrowWrite) => Left("Cannot mutably borrow while immutably borrowed")

      case (VarState.BorrowedWrite, VarAction.Read) => Right(VarState.BorrowedWrite)
      case (VarState.BorrowedWrite, VarAction.BorrowWrite) => Right(VarState.BorrowedWrite)
      case (VarState.BorrowedWrite, VarAction.Move) => Left("Cannot move from mutably borrowed value")
      case (VarState.BorrowedWrite, VarAction.BorrowRead) => Left("Cannot immutably borrow while mutably borrowed")
    }

  def check(): TypedProgram = {
    // Ensure main exists and has no parameters
    val mainFunc = globalContext.functions.getOrElse("main", throw createTypeError("No 'main' function found in the program."))
    if (mainFunc.params.nonEmpty) throw createTypeError("'main' function cannot have parameters.", Some(mainFunc.loc))

    // Check resources, functions
    program.resources.foreach(checkResourceCleanup)
    val typedResources = program.resources.map(checkResource)
    val typedFuncs = program.functions.map(checkFunction)

    TypedProgram(
      program.structs,
      typedResources,
      typedFuncs,
      program.loc
    )
  }

  private def checkResourceCleanup(resource: ResourceDef): Unit = {
    resource.cleanup.foreach { cleanupBlock =>
      val scope = new LocalScope()
      // Resource fields are present as owned mutable variables in cleanup
      resource.fields.foreach { case (name, tpe) =>
        validateType(tpe)
        scope.declare(name, VarInfo(tpe, VarState.Owned, isMutable = true, resource.loc))
      }
      // Typecheck block expecting Unit
      checkStatement(cleanupBlock, scope, Some(UnitType()))
    }
  }

  private def checkResource(resource: ResourceDef): TypedResourceDef = {
    val typedCleanup = resource.cleanup.map { cleanupBlock =>
      val scope = new LocalScope()
      resource.fields.foreach { case (name, tpe) =>
        validateType(tpe)
        scope.declare(name, VarInfo(tpe, VarState.Owned, isMutable = true, resource.loc))
      }
      checkStatement(cleanupBlock, scope, Some(UnitType())) match {
        case tb: TypedBlockStatement => tb
        case other => TypedBlockStatement(List(other), cleanupBlock.loc)
      }
    }
    TypedResourceDef(resource.name, resource.fields, typedCleanup, resource.loc)
  }

  private def checkFunction(f: FuncDef): TypedFuncDef = {
    val scope = new LocalScope()
    // Seed params
    f.params.foreach { p =>
      validateType(p.typ)
      val (isMutable, initState) = p.mode match {
        case ParamMode.Move(mutable) => (mutable, VarState.Owned)
        case ParamMode.Inout         => (true, VarState.BorrowedWrite)
        case ParamMode.Ref           => (false, VarState.BorrowedRead)
      }
      scope.declare(p.name, VarInfo(p.typ, initState, isMutable, p.loc))
    }

    val typedBody = checkStatement(f.body, scope, Some(f.returnType)) match {
      case tb: TypedBlockStatement => tb
      case other => throw new IllegalStateException(s"Expected block statement for function body, got ${other.getClass}")
    }

    TypedFuncDef(f.name, f.params, f.returnType, typedBody, f.loc)
  }

  private def checkStatement(stmt: Statement, scope: LocalScope, expectedReturn: Option[Type]): TypedStatement = stmt match {
    case BlockStatement(stmts, loc) =>
      val inner = scope.cloneScope()
      val typed = stmts.map(s => checkStatement(s, inner, expectedReturn))
      TypedBlockStatement(typed, loc)

    case LetStatement(name, mut, optType, init, loc) =>
      if (scope.contains(name)) throw createTypeError(s"Variable '$name' is already defined in this scope.", Some(loc))
      val typedInit = checkExpression(init, scope)
      val declared = optType match {
        case Some(dt) => validateType(dt); dt
        case None     => typedInit.typ
      }
      if (!areTypesEqual(declared, typedInit.typ))
        throw createTypeError(s"Type mismatch for '$name'. Expected ${typeToString(declared)} but got ${typeToString(typedInit.typ)}.", Some(init.loc))
      // Moves for non-copy initializer
      if (!isCopyType(declared)) handleMove(init, scope)
      scope.declare(name, VarInfo(declared, VarState.Owned, mut, loc))
      TypedLetStatement(name, mut, typedInit, loc)

    case ExpressionStatement(expr, loc) =>
      val texpr = checkExpression(expr, scope)
      TypedExpressionStatement(texpr, loc)

    case AssignmentStatement(target, value, loc) =>
      val typedVal = checkExpression(value, scope)
      val typedTarget = checkPlaceExpression(target, scope, requireMutable = true)
      if (!areTypesEqual(typedTarget.typ, typedVal.typ))
        throw createTypeError(s"Cannot assign value of type ${typeToString(typedVal.typ)} to target of type ${typeToString(typedTarget.typ)}.", Some(value.loc))
      if (!isCopyType(typedVal.typ)) handleMove(value, scope)
      TypedAssignmentStatement(typedTarget, typedVal, loc)

    case ReturnStatement(exprOpt, loc) =>
      val typedOpt = exprOpt.map(e => checkExpression(e, scope))
      val retType = typedOpt.map(_.typ).getOrElse(UnitType())
      val expected = expectedReturn.getOrElse(throw createTypeError("Return statement used outside of a function.", Some(loc)))
      if (!areTypesEqual(retType, expected))
        throw createTypeError(s"Return type mismatch. Expected ${typeToString(expected)} but got ${typeToString(retType)}.", exprOpt.map(_.loc).orElse(Some(loc)))
      typedOpt.foreach { _ =>
        if (!isCopyType(retType)) handleMove(exprOpt.get, scope)
      }
      TypedReturnStatement(typedOpt, loc)
  }

  private def checkExpression(expr: Expression, scope: LocalScope, isManagedCtx: Boolean = false): TypedExpression = expr match {
    case IntLiteral(v, loc)  => TypedIntLiteral(v, IntType(), loc)
    case BoolLiteral(v, loc) => TypedBoolLiteral(v, BoolType(), loc)

    case Variable(name, loc) =>
      val info = scope.get(name).getOrElse(throw createTypeError(s"Variable '$name' not found in this scope.", Some(loc)))
      transition(info.state, VarAction.Read) match {
        case Left(err) => throw createTypeError(s"$err '$name'.", Some(loc))
        case Right(_)  => TypedVariable(name, info.typ, loc)
      }

    case StructLiteral(typeName, fields, loc) =>
      if (isManagedCtx) {
        val (typedFields, structType, isResource) = checkStructLiteral(typeName, fields, scope, loc, isManagedContext = true)
        if (isResource) throw createTypeError(s"Resource '$typeName' cannot be allocated as managed.", Some(loc))
        TypedManagedStructLiteral(typeName, typedFields, ManagedType(structType), loc)
      } else {
        val (typedFields, structType, isResource) = checkStructLiteral(typeName, fields, scope, loc, isManagedContext = false)
        TypedStructLiteral(typeName, typedFields, structType, loc)
      }

    case ManagedStructLiteral(typeName, fields, loc) =>
      val (typedFields, structType, isResource) = checkStructLiteral(typeName, fields, scope, loc, isManagedContext = true)
      if (isResource) throw createTypeError(s"Resource '$typeName' cannot be allocated as managed.", Some(loc))
      TypedManagedStructLiteral(typeName, typedFields, ManagedType(structType), loc)

    case FieldAccess(obj, fieldName, loc) =>
      val tObj = checkExpression(obj, scope)
      val (baseType, isManaged) = tObj.typ match {
        case st: StructNameType => (st, false)
        case ManagedType(inner: StructNameType, _) => (inner, true)
        case ManagedType(inner, _) => throw createTypeError(s"Field access on managed type is only allowed for structs. Found ${typeToString(ManagedType(inner))}", Some(obj.loc))
        case _ => throw createTypeError(s"Field access is only allowed on structs and resources. Found type ${typeToString(tObj.typ)}.", Some(obj.loc))
      }
      val rawFieldType = getFieldType(baseType.name, fieldName, loc)
      val finalType = if (isManaged && isStructOrResourceType(rawFieldType)) ManagedType(rawFieldType) else rawFieldType
      TypedFieldAccess(tObj, fieldName, finalType, loc)

    case FunctionCall(funcExpr, args, loc) =>
      val funcName = funcExpr match {
        case Variable(n, _) => n
        case _ => throw createTypeError("Dynamic function calls are not supported.", Some(funcExpr.loc))
      }
      val funcDef = globalContext.functions.getOrElse(funcName, throw createTypeError(s"Function '$funcName' not found.", Some(funcExpr.loc)))
      if (args.length != funcDef.params.length)
        throw createTypeError(s"Function '$funcName' expects ${funcDef.params.length} arguments, but ${args.length} were provided.", Some(loc))

      val typedArgs = args.zip(funcDef.params).map { case (aExpr, param) =>
        val tArg = checkExpression(aExpr, scope)
        if (!areTypesEqual(tArg.typ, param.typ))
          throw createTypeError(s"Type mismatch for argument to parameter '${param.name}'. Expected ${typeToString(param.typ)} but got ${typeToString(tArg.typ)}.", Some(aExpr.loc))

        param.mode match {
          case ParamMode.Move(_) => if (!isCopyType(tArg.typ)) handleMove(aExpr, scope)
          case ParamMode.Ref      => checkBorrow(aExpr, scope, VarAction.BorrowRead)
          case ParamMode.Inout    => checkBorrow(aExpr, scope, VarAction.BorrowWrite)
        }
        tArg
      }
      TypedFunctionCall(funcName, typedArgs, funcDef.returnType, loc)

    case PrintlnExpression(fmt, args, loc) =>
      val targs = args.map(a => checkExpression(a, scope))
      TypedPrintlnExpression(fmt, targs, UnitType(), loc)

    case BinaryExpression(left, op, right, loc) =>
      val tl = checkExpression(left, scope)
      val tr = checkExpression(right, scope)
      val resType = op match {
        case BinaryOp.Add | BinaryOp.Sub =>
          if (!areTypesEqual(tl.typ, IntType()) || !areTypesEqual(tr.typ, IntType()))
            throw createTypeError(s"Arithmetic operator ${binaryOpToString(op)} requires int operands, but got ${typeToString(tl.typ)} and ${typeToString(tr.typ)}.", Some(loc))
          IntType()
        case BinaryOp.Lt | BinaryOp.Le | BinaryOp.Gt | BinaryOp.Ge =>
          if (!areTypesEqual(tl.typ, IntType()) || !areTypesEqual(tr.typ, IntType()))
            throw createTypeError(s"Comparison operator ${binaryOpToString(op)} requires int operands, but got ${typeToString(tl.typ)} and ${typeToString(tr.typ)}.", Some(loc))
          BoolType()
        case BinaryOp.Eq | BinaryOp.Ne =>
          if (!areTypesEqual(tl.typ, tr.typ))
            throw createTypeError(s"Equality operator ${binaryOpToString(op)} requires same typed operands, but got ${typeToString(tl.typ)} and ${typeToString(tr.typ)}.", Some(loc))
          if (!isCopyType(tl.typ))
            throw createTypeError(s"Equality operator ${binaryOpToString(op)} is only supported for copy types (int, bool, unit), but got ${typeToString(tl.typ)}.", Some(loc))
          BoolType()
      }
      TypedBinaryExpression(tl, op, tr, resType, loc)
  }

  // Place expression (l-value)
  private def checkPlaceExpression(expr: Expression, scope: LocalScope, requireMutable: Boolean): TypedExpression = expr match {
    case Variable(name, loc) =>
      val info = scope.get(name).getOrElse(throw createTypeError(s"Variable '$name' not found.", Some(loc)))
      if (requireMutable && !info.isMutable)
        throw createTypeError(s"Cannot assign to immutable variable '$name'. Use 'let mut' to declare mutable variables or 'inout' for mutable parameters.", Some(loc))
      transition(info.state, VarAction.Read) match {
        case Left(err) => throw createTypeError(s"Cannot use '$name' as it has been moved.", Some(loc))
        case Right(_)  => TypedVariable(name, info.typ, loc)
      }

    case FieldAccess(obj, fieldName, loc) =>
      // The container must be a mutable place; recurse to validate
      val typedObjPlace = checkPlaceExpression(obj, scope, requireMutable)
      val (baseType, isManaged) = typedObjPlace.typ match {
        case st: StructNameType => (st, false)
        case ManagedType(inner: StructNameType, _) => (inner, true)
        case ManagedType(inner, _) => throw createTypeError(s"Field access on managed type is only allowed for structs. Found ${typeToString(ManagedType(inner))}", Some(obj.loc))
        case _ => throw createTypeError(s"Field access is only allowed on structs and resources. Found type ${typeToString(typedObjPlace.typ)}.", Some(obj.loc))
      }
      val raw = getFieldType(baseType.name, fieldName, loc)
      val finalType = if (isManaged && isStructOrResourceType(raw)) ManagedType(raw) else raw
      TypedFieldAccess(typedObjPlace, fieldName, finalType, loc)

    case _ => throw createTypeError("Expression is not a valid assignment target.", Some(expr.loc))
  }

  private def handleMove(sourceExpr: Expression, scope: LocalScope): Unit = sourceExpr match {
    case Variable(name, loc) =>
      val info = scope.get(name).getOrElse(throw new IllegalStateException("Variable disappeared during move check"))
      transition(info.state, VarAction.Move) match {
        case Left(err) => throw createTypeError(s"$err '$name'.", Some(loc))
        case Right(newState) => scope.update(name, info.copy(state = newState))
      }
    case FieldAccess(obj, _, _) =>
      // Moving from a field is not implemented for now; it's a complex scenario (requires ownership of container).
      // ignore non-variable moves for now.
      ()
    case _ => ()
  }

  private def checkBorrow(arg: Expression, scope: LocalScope, action: VarAction): Unit = arg match {
    case Variable(name, loc) =>
      val info = scope.get(name).getOrElse(throw new IllegalStateException("Variable disappeared during borrow check"))
      if (action == VarAction.BorrowWrite && !info.isMutable)
        throw createTypeError(s"Cannot mutably borrow immutable variable '$name'. Mark it as 'mut' or pass it to an 'inout' parameter.", Some(loc))
      transition(info.state, action) match {
        case Left(err) => throw createTypeError(s"$err '$name'.", Some(loc))
        case Right(newState) =>
          // We do not mutate VarState here to emulate temporary borrow lifetimes (no lifetimes implemented).
          ()
      }

    case FieldAccess(obj, _, _) =>
      checkBorrow(obj, scope, action)

    case _ => throw createTypeError("Cannot borrow from a temporary value.", Some(arg.loc))
  }

  private def checkStructLiteral(
      typeName: String,
      values: List[(String, Expression)],
      scope: LocalScope,
      loc: SourceLocation,
      isManagedContext: Boolean
  ): (List[(String, TypedExpression)], StructNameType, Boolean) = {
    val defnEither: Either[StructDef, ResourceDef] =
      globalContext.structs.get(typeName).map(Left(_)).orElse(globalContext.resources.get(typeName).map(Right(_))).getOrElse(
        throw createTypeError(s"Unknown struct or resource '$typeName'.", Some(loc))
      )

    val (expectedFields, isResource) = defnEither match {
      case Left(sd) => (sd.fields, false)
      case Right(rd) => (rd.fields, true)
    }

    val provided = values.map(_._1).toSet
    val expectedSet = expectedFields.map(_._1).toSet
    if (provided != expectedSet)
      throw createTypeError(s"'$typeName' initialization has incorrect fields. Expected: ${expectedSet.mkString(", ")}, Got: ${provided.mkString(", ")}.", Some(loc))

    val typed = values.map { case (fname, fexpr) =>
      val texpr = checkExpression(fexpr, scope, isManagedContext)
      val expectedType = expectedFields.find(_._1 == fname).get._2
      val finalExpected = if (isManagedContext && isStructOrResourceType(expectedType)) ManagedType(expectedType) else expectedType
      if (!areTypesEqual(texpr.typ, finalExpected))
        throw createTypeError(s"Type mismatch for field '$fname' in '$typeName' initialization. Expected ${typeToString(finalExpected)} but got ${typeToString(texpr.typ)}.", Some(fexpr.loc))
      if (!isCopyType(texpr.typ)) handleMove(fexpr, scope)
      (fname, texpr)
    }
    (typed, StructNameType(typeName), isResource)
  }

  private def getFieldType(structName: String, fieldName: String, loc: SourceLocation): Type = {
    val definition = globalContext.structs.get(structName).map(Left(_)).orElse(globalContext.resources.get(structName).map(Right(_))).getOrElse(
      throw new IllegalStateException(s"Definition for '$structName' not found in global context.")
    )
    val fields = definition match { case Left(s) => s.fields; case Right(r) => r.fields }
    fields.find(_._1 == fieldName).map(_._2).getOrElse(throw createTypeError(s"Type '$structName' has no field named '$fieldName'.", Some(loc)))
  }

  private def isStructOrResourceType(t: Type): Boolean = t match {
    case StructNameType(name, _) => globalContext.structs.contains(name) || globalContext.resources.contains(name)
    case _ => false
  }

  private def validateType(t: Type): Unit = t match {
    case StructNameType(name, loc) =>
      if (!globalContext.structs.contains(name) && !globalContext.resources.contains(name))
        throw createTypeError(s"Unknown type '$name'.", loc)
    case ManagedType(inner, _) => validateType(inner)
    case _ => ()
  }

  private def typeToString(t: Type): String = t match {
    case IntType(_) => "int"
    case BoolType(_) => "bool"
    case UnitType(_) => "unit"
    case StructNameType(n, _) => n
    case ManagedType(inner, _) => s"managed ${typeToString(inner)}"
  }

  private def areTypesEqual(t1: Type, t2: Type): Boolean = (t1, t2) match {
    case (IntType(_), IntType(_)) => true
    case (BoolType(_), BoolType(_)) => true
    case (UnitType(_), UnitType(_)) => true
    case (StructNameType(n1, _), StructNameType(n2, _)) => n1 == n2
    case (ManagedType(i1, _), ManagedType(i2, _)) => areTypesEqual(i1, i2)
    case _ => false
  }

  private def isCopyType(t: Type): Boolean = t match {
    case IntType(_) | BoolType(_) | UnitType(_) => true
    case ManagedType(_, _) => true
    case _ => false
  }

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
