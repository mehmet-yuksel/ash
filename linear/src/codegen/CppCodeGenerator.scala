package linear.codegen

import linear.parser._
import linear.typechecker.typed._

import scala.collection.mutable

class CppCodeGenerator(program: TypedProgram) {

  private val forwardDeclarations = new mutable.StringBuilder()
  private val implementations = new mutable.StringBuilder()

  // A map from struct name to its definition for easy lookup.
  private val structDefs: Map[String, StructDef] =
    program.structs.map(s => s.name -> s).toMap
  
  // A map from resource name to its definition for easy lookup.
  private val resourceDefs: Map[String, TypedResourceDef] =
    program.resources.map(r => r.name -> r).toMap

  def generate(): String = {
    // --- Standard Headers ---
    forwardDeclarations.append("#include <iostream>\n")
    forwardDeclarations.append("#include <utility> // For std::move\n")
    forwardDeclarations.append("#include <print> // For std::println\n")
    forwardDeclarations.append("#include \"gc.h\" // For garbage collection\n\n")

    // --- Struct and Resource Definitions ---
    program.structs.foreach(generateStructDef)
    program.resources.foreach(generateResourceDef)

    // --- Function Forward Declarations ---
    program.functions.foreach(generateFunctionForwardDecl)
    forwardDeclarations.append("\n")

    // --- Function Implementations ---
    program.functions.foreach(generateFunctionImpl)

    // --- Main Entry Point ---
    // C++ main must return int
    implementations.append("int main() {\n")
    implementations.append("    GC_init();\n")
    implementations.append("    main_ash();\n")
    implementations.append("    return 0;\n")
    implementations.append("}\n")

    forwardDeclarations.toString() + implementations.toString()
  }

  private def generateType(t: Type): String = t match {
    case IntType(_)              => "int"
    case BoolType(_)             => "bool"
    case UnitType(_)             => "void"
    case StructNameType(name, _) => name
    case ManagedType(innerType, _) => s"${generateType(innerType)}*"
  }

  private def generateStructDef(s: StructDef): Unit = {
    forwardDeclarations.append(s"struct ${s.name} {\n")
    s.fields.foreach { case (fieldName, fieldType) =>
      forwardDeclarations.append(
        s"    ${generateType(fieldType)} ${fieldName};\n"
      )
    }
    forwardDeclarations.append("};\n\n")
  }

  private def generateResourceDef(r: TypedResourceDef): Unit = {
    forwardDeclarations.append(s"struct ${r.name} {\n")
    r.fields.foreach { case (fieldName, fieldType) =>
      forwardDeclarations.append(
        s"    ${generateType(fieldType)} ${fieldName};\n"
      )
    }
    
    // Generate destructor if cleanup block exists
    r.cleanup match {
      case Some(cleanupBlock) =>
        forwardDeclarations.append(s"    ~${r.name}() {\n")
        cleanupBlock match {
          case TypedBlockStatement(statements, _) =>
            statements.foreach(stmt => {
              forwardDeclarations.append("        ")
              generateStatementInline(stmt)
            })
        }
        forwardDeclarations.append("    }\n")
      case None => // No cleanup needed
    }
    
    forwardDeclarations.append("};\n\n")
  }

  private def generateFunctionForwardDecl(f: TypedFuncDef): Unit = {
    val c_name = if (f.name == "main") "main_ash" else f.name
    forwardDeclarations.append(
      s"${generateType(f.returnType)} ${c_name}(${generateParams(f.params)});\n"
    )
  }

  private def generateFunctionImpl(f: TypedFuncDef): Unit = {
    val c_name = if (f.name == "main") "main_ash" else f.name
    implementations.append(
      s"${generateType(f.returnType)} ${c_name}(${generateParams(f.params)}) {\n"
    )
    f.body.statements.foreach(s => generateStatement(s, 1))
    implementations.append("}\n\n")
  }

  private def generateParams(params: List[Param]): String = {
    params
      .map { p =>
        val paramType = generateType(p.typ)
        p.mode match {
          case ParamMode.Move(_) => s"$paramType ${p.name}"
          case ParamMode.Ref     => s"const $paramType& ${p.name}"
          case ParamMode.Inout   => s"$paramType& ${p.name}"
        }
      }
      .mkString(", ")
  }

  private def indent(level: Int): String = "    " * level

  private def generateStatementInline(stmt: TypedStatement): Unit = {
    stmt match {
      case TypedBlockStatement(statements, _) =>
        forwardDeclarations.append("{\n")
        statements.foreach(s => {
          forwardDeclarations.append("            ")
          generateStatementInline(s)
        })
        forwardDeclarations.append("        }\n")

      case TypedLetStatement(varName, isMutable, init, _) =>
        val initExpr = generateExpression(init)
        val typeName = generateType(init.typ)
        forwardDeclarations.append(s"$typeName $varName = std::move($initExpr);\n")

      case TypedExpressionStatement(expr, _) =>
        forwardDeclarations.append(s"${generateExpression(expr)};\n")

      case TypedReturnStatement(exprOpt, _) =>
        exprOpt match {
          case Some(expr) =>
            forwardDeclarations.append(s"return ${generateExpression(expr)};\n")
          case None => forwardDeclarations.append("return;\n")
        }

      case TypedAssignmentStatement(target, value, _) =>
        forwardDeclarations.append(
          s"${generateExpression(target)} = std::move(${generateExpression(value)});\n"
        )
    }
  }

  private def generateStatement(
      stmt: TypedStatement,
      indentLevel: Int
  ): Unit = {
    implementations.append(indent(indentLevel))
    stmt match {
      case TypedBlockStatement(statements, _) =>
        implementations.delete(
          implementations.length - indent(indentLevel).length,
          implementations.length
        ) // remove indent
        implementations.append("{\n")
        statements.foreach(s => generateStatement(s, indentLevel + 1))
        implementations.append(s"${indent(indentLevel)}}\n")

      case TypedLetStatement(varName, isMutable, init, _) =>
        // C++ locals are mutable by default, so `isMutable` is ignored for now.
        // For move semantics, we use std::move on the initializer.
        val initExpr = generateExpression(init)
        val typeName = generateType(init.typ)
        implementations.append(s"$typeName $varName = std::move($initExpr);\n")

      case TypedExpressionStatement(expr, _) =>
        implementations.append(s"${generateExpression(expr)};\n")

      case TypedReturnStatement(exprOpt, _) =>
        exprOpt match {
          case Some(expr) =>
            implementations.append(s"return ${generateExpression(expr)};\n")
          case None => implementations.append("return;\n")
        }

      case TypedAssignmentStatement(target, value, _) =>
        implementations.append(
          s"${generateExpression(target)} = std::move(${generateExpression(value)});\n"
        )
    }
  }

  private def generateExpression(expr: TypedExpression): String = expr match {
    case TypedIntLiteral(value, _, _)  => value.toString
    case TypedBoolLiteral(value, _, _) => if (value) "true" else "false"
    case TypedVariable(name, _, _)     => name
    case TypedFieldAccess(obj, fieldName, _, _) =>
      val objExpr = generateExpression(obj)
      // Use -> for managed types (pointers), . for regular structs
      obj.typ match {
        case ManagedType(_, _) => s"$objExpr->$fieldName"
        case _ => s"$objExpr.$fieldName"
      }
    case TypedFunctionCall(funcName, args, _, _) =>
      val c_name = if (funcName == "main") "main_ash" else funcName
      val argList = args.map(generateExpression).mkString(", ")
      s"$c_name($argList)"
    case TypedStructLiteral(typeName, values, _, _) =>
      // C++ aggregate initialization requires fields in declaration order.
      val fields = structDefs.get(typeName).map(_.fields)
        .orElse(resourceDefs.get(typeName).map(_.fields))
        .getOrElse(throw new RuntimeException(s"Unknown type: $typeName"))
      
      val orderedValues = fields.map { case (fieldName, _) =>
        val (_, expr) = values.find(_._1 == fieldName).get
        generateExpression(expr)
      }
      s"$typeName{${orderedValues.mkString(", ")}}"
    case TypedManagedStructLiteral(typeName, values, _, _) =>
      // For managed types, allocate using GC_malloc and use placement new
      val fields = structDefs.get(typeName).map(_.fields)
        .orElse(resourceDefs.get(typeName).map(_.fields))
        .getOrElse(throw new RuntimeException(s"Unknown type: $typeName"))
      
      val orderedValues = fields.map { case (fieldName, _) =>
        val (_, expr) = values.find(_._1 == fieldName).get
        generateExpression(expr)
      }
      s"new(GC_malloc(sizeof($typeName))) $typeName{${orderedValues.mkString(", ")}}"
    case TypedPrintlnExpression(formatString, args, _, _) =>
      val argList = args.map(generateExpression).mkString(", ")
      if (args.nonEmpty) {
        s"std::println(\"$formatString\", $argList)"
      } else {
        s"std::println(\"$formatString\")"
      }
  }
}
