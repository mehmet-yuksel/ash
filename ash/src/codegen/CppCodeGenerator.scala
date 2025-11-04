package ash.codegen

import ash.parser._
import ash.typechecker.typed._
import scala.collection.mutable

class CodeWriter {
  private val buffer = new mutable.StringBuilder()
  private var indentLevel = 0
  private val indentString = "    " // 4 spaces

  def indent(): Unit = indentLevel += 1
  def dedent(): Unit = indentLevel = math.max(0, indentLevel - 1)

  def write(text: String): Unit = {
    buffer.append(text)
  }

  def writeLine(text: String = ""): Unit = {
    if (text.nonEmpty) {
      buffer.append(indentString * indentLevel)
      buffer.append(text)
    }
    buffer.append("\n")
  }

  def writeIndented(text: String): Unit = {
    buffer.append(indentString * indentLevel)
    buffer.append(text)
  }

  def inBlock(opening: String = "{", closing: String = "}")(body: => Unit): Unit = {
    writeLine(opening)
    indent()
    body
    dedent()
    writeIndented(closing)
  }

  def inInlineBlock(body: => Unit): Unit = {
    write(" { ")
    body
    write(" }")
  }

  def inNamespace(name: String)(body: => Unit): Unit = {
    writeLine(s"namespace $name {")
    indent()
    body
    dedent()
    writeLine("}")
  }

  def writeStruct(name: String, isClass: Boolean = false)(body: => Unit): Unit = {
    val keyword = if (isClass) "class" else "struct"
    writeLine(s"$keyword $name {")
    if (isClass) writeLine("public:")
    indent()
    body
    dedent()
    writeLine("};")
    writeLine()
  }

  def writeFunction(
    returnType: String,
    name: String,
    params: String,
    isDeclaration: Boolean = false
  )(body: => Unit = ()): Unit = {
    write(s"$returnType $name($params)")
    if (isDeclaration) {
      writeLine(";")
    } else {
      write(" ")
      inBlock() { body }
      writeLine()
    }
  }

  def writeConstructor(
    className: String,
    params: String,
    initList: Option[String] = None,
    isExplicit: Boolean = false,
    isNoexcept: Boolean = false
  )(body: => Unit): Unit = {
    if (isExplicit) write("explicit ")
    write(s"$className($params)")
    if (isNoexcept) write(" noexcept")
    initList.foreach(init => write(s" : $init"))
    write(" ")
    inBlock() { body }
    writeLine()
  }

  def writeComment(comment: String, style: CommentStyle = CommentStyle.SingleLine): Unit = {
    style match {
      case CommentStyle.SingleLine => writeLine(s"// $comment")
      case CommentStyle.Block =>
        writeLine("/*")
        comment.split("\n").foreach(line => writeLine(s" * $line"))
        writeLine(" */")
    }
  }

  def writeSectionHeader(title: String): Unit = {
    writeLine(s"// --- $title ---")
  }

  def result(): String = buffer.toString()
  def clear(): Unit = {
    buffer.clear()
    indentLevel = 0
  }
}

enum CommentStyle {
  case SingleLine, Block
}

class CppCodeGenerator(program: TypedProgram) {

  private val headers = new CodeWriter()
  private val forward = new CodeWriter()
  private val impl = new CodeWriter()

  // Maps for easy lookup
  private val structDefs: Map[String, StructDef] =
    program.structs.map(s => s.name -> s).toMap

  private val resourceDefs: Map[String, TypedResourceDef] =
    program.resources.map(r => r.name -> r).toMap

  def generate(): String = {
    generateHeaders()
    generateForwardDeclarations()
    generateImplementations()
    generateMain()

    headers.result() + forward.result() + impl.result()
  }

  private def generateHeaders(): Unit = {
    headers.writeSectionHeader("Standard Headers")
    headers.writeLine("#include <iostream>")
    headers.writeLine("#include <utility>  // For std::move")
    headers.writeLine("#include <print>    // For std::println")
    headers.writeLine("#include \"gc.h\"     // For garbage collection")
    headers.writeLine()
  }

  private def generateForwardDeclarations(): Unit = {
    forward.writeSectionHeader("Type Definitions")
    program.structs.foreach(generateStructDef)
    program.resources.foreach(generateResourceDef)

    forward.writeSectionHeader("Function Forward Declarations")
    program.functions.foreach(generateFunctionForwardDecl)
    forward.writeLine()
  }

  private def generateImplementations(): Unit = {
    impl.writeSectionHeader("Function Implementations")
    program.functions.foreach(generateFunctionImpl)
  }

  private def generateMain(): Unit = {
    impl.writeSectionHeader("Main Entry Point")
    impl.writeFunction("int", "main", "") {
      impl.writeLine("GC_init();")
      impl.writeLine("main_ash();")
      impl.writeLine("return 0;")
    }
  }

  private def generateType(t: Type): String = t match {
    case IntType(_)                => "int"
    case BoolType(_)               => "bool"
    case UnitType(_)               => "void"
    case StructNameType(name, _)   => name
    case ManagedType(innerType, _) => s"${generateType(innerType)}*"
  }

  private def generateStructDef(s: StructDef): Unit = {
    forward.writeStruct(s.name) {
      s.fields.foreach { case (fieldName, fieldType) =>
        forward.writeLine(s"${generateType(fieldType)} $fieldName;")
      }
    }
  }

  private def generateResourceDef(r: TypedResourceDef): Unit = {
    forward.writeStruct(r.name) {
      // Fields
      r.fields.foreach { case (fieldName, fieldType) =>
        forward.writeLine(s"${generateType(fieldType)} $fieldName;")
      }
      forward.writeLine("bool owns_resource = true;")
      forward.writeLine()

      // Constructor
      val constructorParams = r.fields
        .map { case (fieldName, fieldType) =>
          s"${generateType(fieldType)} ${fieldName}_in"
        }
        .mkString(", ")

      val initList = if (r.fields.nonEmpty) {
        Some(r.fields.map { case (fieldName, _) =>
          s"$fieldName(std::move(${fieldName}_in))"
        }.mkString(", "))
      } else None

      forward.writeConstructor(r.name, constructorParams, initList, isExplicit = true) {}

      forward.writeSectionHeader("Rule of 5")

      // Destructor
      forward.write(s"~${r.name}()")
      forward.write(" ")
      forward.inBlock() {
        r.cleanup.foreach { cleanupBlock =>
          forward.writeLine("if (owns_resource) {")
          forward.indent()
          generateResourceCleanup(cleanupBlock, forward)
          forward.dedent()
          forward.writeLine("}")
        }
      }
      forward.writeLine()

      // Delete copy operations
      forward.writeLine(s"${r.name}(const ${r.name}&) = delete;")
      forward.writeLine(s"${r.name}& operator=(const ${r.name}&) = delete;")
      forward.writeLine()

      // Move constructor
      generateMoveConstructor(r)

      // Move assignment operator
      generateMoveAssignment(r)
    }
  }

  private def generateMoveConstructor(r: TypedResourceDef): Unit = {
    val moveCtorFieldInits = r.fields.map { case (fieldName, _) =>
      s"$fieldName(std::move(other.$fieldName))"
    }
    val initList = (moveCtorFieldInits :+ "owns_resource(other.owns_resource)").mkString(", ")

    forward.writeConstructor(
      r.name,
      s"${r.name}&& other",
      Some(initList),
      isNoexcept = true
    ) {
      forward.writeLine("other.owns_resource = false;")
    }
  }

  private def generateMoveAssignment(r: TypedResourceDef): Unit = {
    forward.write(s"${r.name}& operator=(${r.name}&& other) noexcept")
    forward.write(" ")
    forward.inBlock() {
      forward.writeLine("if (this != &other) {")
      forward.indent()

      // Cleanup existing resource
      r.cleanup.foreach { cleanupBlock =>
        forward.writeLine("if (owns_resource) {")
        forward.indent()
        generateResourceCleanup(cleanupBlock, forward)
        forward.dedent()
        forward.writeLine("}")
      }

      // Move fields from other
      r.fields.foreach { case (fieldName, _) =>
        forward.writeLine(s"$fieldName = std::move(other.$fieldName);")
      }

      // Transfer ownership
      forward.writeLine("owns_resource = other.owns_resource;")
      forward.writeLine("other.owns_resource = false;")

      forward.dedent()
      forward.writeLine("}")
      forward.writeLine("return *this;")
    }
  }

  private def generateResourceCleanup(cleanup: TypedStatement, writer: CodeWriter): Unit = {
    cleanup match {
      case TypedBlockStatement(statements, _) =>
        statements.foreach(stmt => generateStatement(stmt, writer))
    }
  }

  private def generateFunctionForwardDecl(f: TypedFuncDef): Unit = {
    val cName = if (f.name == "main") "main_ash" else f.name
    forward.writeFunction(
      generateType(f.returnType),
      cName,
      generateParams(f.params),
      isDeclaration = true
    )()
  }

  private def generateFunctionImpl(f: TypedFuncDef): Unit = {
    val cName = if (f.name == "main") "main_ash" else f.name
    impl.writeFunction(
      generateType(f.returnType),
      cName,
      generateParams(f.params)
    ) {
      f.body.statements.foreach(stmt => generateStatement(stmt, impl))
    }
  }

  private def generateParams(params: List[Param]): String = {
    params.map { p =>
      val paramType = generateType(p.typ)
      p.mode match {
        case ParamMode.Move(_) => s"$paramType ${p.name}"
        case ParamMode.Ref     => s"const $paramType& ${p.name}"
        case ParamMode.Inout   => s"$paramType& ${p.name}"
      }
    }.mkString(", ")
  }

  private def generateStatement(stmt: TypedStatement, writer: CodeWriter): Unit = {
    stmt match {
      case TypedBlockStatement(statements, _) =>
        writer.inBlock() {
          statements.foreach(s => generateStatement(s, writer))
        }
        writer.writeLine()

      case TypedLetStatement(varName, isMutable, init, _) =>
        val initExpr = generateExpression(init)
        val typeName = generateType(init.typ)
        writer.writeLine(s"$typeName $varName = std::move($initExpr);")

      case TypedExpressionStatement(expr, _) =>
        writer.writeLine(s"${generateExpression(expr)};")

      case TypedReturnStatement(exprOpt, _) =>
        exprOpt match {
          case Some(expr) =>
            writer.writeLine(s"return ${generateExpression(expr)};")
          case None =>
            writer.writeLine("return;")
        }

      case TypedAssignmentStatement(target, value, _) =>
        writer.writeLine(
          s"${generateExpression(target)} = std::move(${generateExpression(value)});"
        )
    }
  }

  private def generateExpression(expr: TypedExpression): String = expr match {
    case TypedIntLiteral(value, _, _)  => value.toString
    case TypedBoolLiteral(value, _, _) => if (value) "true" else "false"
    case TypedVariable(name, _, _)     => name

    case TypedFieldAccess(obj, fieldName, _, _) =>
      val objExpr = generateExpression(obj)
      obj.typ match {
        case ManagedType(_, _) => s"$objExpr->$fieldName"
        case _                 => s"$objExpr.$fieldName"
      }

    case TypedFunctionCall(funcName, args, _, _) =>
      val cName = if (funcName == "main") "main_ash" else funcName
      val argList = args.map(generateExpression).mkString(", ")
      s"$cName($argList)"

    case TypedStructLiteral(typeName, values, _, _) =>
      generateStructOrResourceLiteral(typeName, values, managed = false)

    case TypedManagedStructLiteral(typeName, values, _, _) =>
      generateStructOrResourceLiteral(typeName, values, managed = true)

    case TypedPrintlnExpression(formatString, args, _, _) =>
      if (args.nonEmpty) {
        val argList = args.map(generateExpression).mkString(", ")
        s"std::println(\"$formatString\", $argList)"
      } else {
        s"std::println(\"$formatString\")"
      }

    case TypedBinaryExpression(left, op, right, _, _) =>
      val leftExpr = generateExpression(left)
      val rightExpr = generateExpression(right)
      val opStr = binaryOpToString(op)
      s"($leftExpr $opStr $rightExpr)"
  }

  private def generateStructOrResourceLiteral(
    typeName: String,
    values: List[(String, TypedExpression)],
    managed: Boolean
  ): String = {
    val isResource = resourceDefs.contains(typeName)
    val fields = getFieldsForType(typeName)

    val orderedValues = fields.map { case (fieldName, _) =>
      val (_, expr) = values.find(_._1 == fieldName).get
      generateExpression(expr)
    }.mkString(", ")

    if (managed) {
      s"new(GC_malloc(sizeof($typeName))) $typeName{$orderedValues}"
    } else if (isResource) {
      s"$typeName($orderedValues)"
    } else {
      s"$typeName{$orderedValues}"
    }
  }

  private def getFieldsForType(typeName: String): List[(String, Type)] = {
    structDefs
      .get(typeName)
      .map(_.fields)
      .orElse(resourceDefs.get(typeName).map(_.fields))
      .getOrElse(throw new RuntimeException(s"Unknown type: $typeName"))
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
