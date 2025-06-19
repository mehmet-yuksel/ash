package ash.parser

import utest._
import ash.parser._

object ParserTests extends utest.TestSuite {
  def tests = Tests {
    test("basic parsing") {
      test("struct definition") {
        val code = """
          struct Point {
            x: int,
            y: int
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        assert(program.structs.length == 1)
        val structDef = program.structs.head
        assert(structDef.name == "Point")
        assert(structDef.fields.length == 2)
        assert(structDef.fields(0)._1 == "x")
        assert(structDef.fields(0)._2.isInstanceOf[IntType])
        assert(structDef.fields(1)._1 == "y")
        assert(structDef.fields(1)._2.isInstanceOf[IntType])
      }
      
      test("resource definition") {
        val code = """
          resource File {
            fd: int,
            name: bool
            
            cleanup {
              close(fd);
            }
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        assert(program.resources.length == 1)
        val resourceDef = program.resources.head
        assert(resourceDef.name == "File")
        assert(resourceDef.fields.length == 2)
        assert(resourceDef.fields(0)._1 == "fd")
        assert(resourceDef.fields(0)._2.isInstanceOf[IntType])
        assert(resourceDef.fields(1)._1 == "name")
        assert(resourceDef.fields(1)._2.isInstanceOf[BoolType])
        assert(resourceDef.cleanup.isDefined)
      }
      
      test("function definition") {
        val code = """
          fn main() -> unit {
            let x = 42;
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        assert(program.functions.length == 1)
        val funcDef = program.functions.head
        assert(funcDef.name == "main")
        assert(funcDef.params.isEmpty)
        assert(funcDef.returnType.isInstanceOf[UnitType])
        assert(funcDef.body.isInstanceOf[BlockStatement])
      }
    }
    
    test("expressions") {
      test("integer literal") {
        val code = """
          fn main() -> unit {
            let x = 42;
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val funcDef = program.functions.head
        val block = funcDef.body.asInstanceOf[BlockStatement]
        val letStmt = block.statements.head.asInstanceOf[LetStatement]
        val intLit = letStmt.init.asInstanceOf[IntLiteral]
        assert(intLit.value == 42)
      }
      
      test("boolean literal") {
        val code = """
          fn main() -> unit {
            let x = true;
            let y = false;
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val funcDef = program.functions.head
        val block = funcDef.body.asInstanceOf[BlockStatement]
        val letStmt1 = block.statements(0).asInstanceOf[LetStatement]
        val letStmt2 = block.statements(1).asInstanceOf[LetStatement]
        
        val boolLit1 = letStmt1.init.asInstanceOf[BoolLiteral]
        val boolLit2 = letStmt2.init.asInstanceOf[BoolLiteral]
        assert(boolLit1.value == true)
        assert(boolLit2.value == false)
      }
      
      test("variable reference") {
        val code = """
          fn main() -> unit {
            let x = 42;
            let y = x;
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val funcDef = program.functions.head
        val block = funcDef.body.asInstanceOf[BlockStatement]
        val letStmt2 = block.statements(1).asInstanceOf[LetStatement]
        val variable = letStmt2.init.asInstanceOf[Variable]
        assert(variable.name == "x")
      }
      
      test("struct literal") {
        val code = """
          fn main() -> unit {
            let p = Point { x: 10, y: 20 };
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val funcDef = program.functions.head
        val block = funcDef.body.asInstanceOf[BlockStatement]
        val letStmt = block.statements.head.asInstanceOf[LetStatement]
        val structLit = letStmt.init.asInstanceOf[StructLiteral]
        
        assert(structLit.typeName == "Point")
        assert(structLit.values.length == 2)
        assert(structLit.values(0)._1 == "x")
        assert(structLit.values(0)._2.asInstanceOf[IntLiteral].value == 10)
        assert(structLit.values(1)._1 == "y")
        assert(structLit.values(1)._2.asInstanceOf[IntLiteral].value == 20)
      }
      
      test("managed struct literal") {
        val code = """
          fn main() -> unit {
            let p = managed Point { x: 10, y: 20 };
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val funcDef = program.functions.head
        val block = funcDef.body.asInstanceOf[BlockStatement]
        val letStmt = block.statements.head.asInstanceOf[LetStatement]
        val managedStructLit = letStmt.init.asInstanceOf[ManagedStructLiteral]
        
        assert(managedStructLit.typeName == "Point")
        assert(managedStructLit.values.length == 2)
        assert(managedStructLit.values(0)._1 == "x")
        assert(managedStructLit.values(0)._2.asInstanceOf[IntLiteral].value == 10)
        assert(managedStructLit.values(1)._1 == "y")
        assert(managedStructLit.values(1)._2.asInstanceOf[IntLiteral].value == 20)
      }
      
      test("field access") {
        val code = """
          fn main() -> unit {
            let x = p.x;
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val funcDef = program.functions.head
        val block = funcDef.body.asInstanceOf[BlockStatement]
        val letStmt = block.statements.head.asInstanceOf[LetStatement]
        val fieldAccess = letStmt.init.asInstanceOf[FieldAccess]
        
        assert(fieldAccess.obj.asInstanceOf[Variable].name == "p")
        assert(fieldAccess.fieldName == "x")
      }
      
      test("function call") {
        val code = """
          fn main() -> unit {
            foo();
            bar(x, y);
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val funcDef = program.functions.head
        val block = funcDef.body.asInstanceOf[BlockStatement]
        val exprStmt1 = block.statements(0).asInstanceOf[ExpressionStatement]
        val exprStmt2 = block.statements(1).asInstanceOf[ExpressionStatement]
        
        val call1 = exprStmt1.expr.asInstanceOf[FunctionCall]
        val call2 = exprStmt2.expr.asInstanceOf[FunctionCall]
        
        assert(call1.funcName.asInstanceOf[Variable].name == "foo")
        assert(call1.args.isEmpty)
        
        assert(call2.funcName.asInstanceOf[Variable].name == "bar")
        assert(call2.args.length == 2)
        assert(call2.args(0).asInstanceOf[Variable].name == "x")
        assert(call2.args(1).asInstanceOf[Variable].name == "y")
      }
      
      test("println expression") {
        val code = """
          fn main() -> unit {
            println!("Hello {}", name);
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val funcDef = program.functions.head
        val block = funcDef.body.asInstanceOf[BlockStatement]
        val exprStmt = block.statements.head.asInstanceOf[ExpressionStatement]
        val println = exprStmt.expr.asInstanceOf[PrintlnExpression]
        
        assert(println.formatString == "Hello {}")
        assert(println.args.length == 1)
        assert(println.args(0).asInstanceOf[Variable].name == "name")
      }
    }
    
    test("statements") {
      test("let statement") {
        val code = """
          fn main() -> unit {
            let x = 42;
            let mut y: int = 10;
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val funcDef = program.functions.head
        val block = funcDef.body.asInstanceOf[BlockStatement]
        val letStmt1 = block.statements(0).asInstanceOf[LetStatement]
        val letStmt2 = block.statements(1).asInstanceOf[LetStatement]
        
        assert(letStmt1.varName == "x")
        assert(letStmt1.isMutable == false)
        assert(letStmt1.typeAnnotation.isEmpty)
        assert(letStmt1.init.asInstanceOf[IntLiteral].value == 42)
        
        assert(letStmt2.varName == "y")
        assert(letStmt2.isMutable == true)
        assert(letStmt2.typeAnnotation.isDefined)
        assert(letStmt2.typeAnnotation.get.isInstanceOf[IntType])
        assert(letStmt2.init.asInstanceOf[IntLiteral].value == 10)
      }
      
      test("return statement") {
        val code = """
          fn test() -> int {
            return 42;
          }
          fn test2() -> unit {
            return;
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val func1 = program.functions(0)
        val func2 = program.functions(1)
        
        val block1 = func1.body.asInstanceOf[BlockStatement]
        val block2 = func2.body.asInstanceOf[BlockStatement]
        
        val returnStmt1 = block1.statements.head.asInstanceOf[ReturnStatement]
        val returnStmt2 = block2.statements.head.asInstanceOf[ReturnStatement]
        
        assert(returnStmt1.expr.isDefined)
        assert(returnStmt1.expr.get.asInstanceOf[IntLiteral].value == 42)
        
        assert(returnStmt2.expr.isEmpty)
      }
      
      test("assignment statement") {
        val code = """
          fn main() -> unit {
            x = 42;
            p.x = 10;
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val funcDef = program.functions.head
        val block = funcDef.body.asInstanceOf[BlockStatement]
        val assignStmt1 = block.statements(0).asInstanceOf[AssignmentStatement]
        val assignStmt2 = block.statements(1).asInstanceOf[AssignmentStatement]
        
        assert(assignStmt1.target.asInstanceOf[Variable].name == "x")
        assert(assignStmt1.value.asInstanceOf[IntLiteral].value == 42)
        
        val fieldAccess = assignStmt2.target.asInstanceOf[FieldAccess]
        assert(fieldAccess.obj.asInstanceOf[Variable].name == "p")
        assert(fieldAccess.fieldName == "x")
        assert(assignStmt2.value.asInstanceOf[IntLiteral].value == 10)
      }
      
      test("expression statement") {
        val code = """
          fn main() -> unit {
            foo();
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val funcDef = program.functions.head
        val block = funcDef.body.asInstanceOf[BlockStatement]
        val exprStmt = block.statements.head.asInstanceOf[ExpressionStatement]
        
        val call = exprStmt.expr.asInstanceOf[FunctionCall]
        assert(call.funcName.asInstanceOf[Variable].name == "foo")
      }
      
      test("block statement") {
        val code = """
          fn main() -> unit {
            {
              let x = 42;
              foo();
            }
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val funcDef = program.functions.head
        val outerBlock = funcDef.body.asInstanceOf[BlockStatement]
        val innerBlock = outerBlock.statements.head.asInstanceOf[BlockStatement]
        
        assert(innerBlock.statements.length == 2)
        assert(innerBlock.statements(0).isInstanceOf[LetStatement])
        assert(innerBlock.statements(1).isInstanceOf[ExpressionStatement])
      }
    }
    
    test("types") {
      test("basic types") {
        val code = """
          struct Test {
            i: int,
            b: bool,
            u: unit
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val structDef = program.structs.head
        assert(structDef.fields(0)._2.isInstanceOf[IntType])
        assert(structDef.fields(1)._2.isInstanceOf[BoolType])
        assert(structDef.fields(2)._2.isInstanceOf[UnitType])
      }
      
      test("struct name type") {
        val code = """
          struct Container {
            point: Point
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val structDef = program.structs.head
        val structNameType = structDef.fields.head._2.asInstanceOf[StructNameType]
        assert(structNameType.name == "Point")
      }
      
      test("managed type") {
        val code = """
          struct Container {
            managed_point: managed Point,
            managed_int: managed int
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val structDef = program.structs.head
        val managedType1 = structDef.fields(0)._2.asInstanceOf[ManagedType]
        val managedType2 = structDef.fields(1)._2.asInstanceOf[ManagedType]
        
        assert(managedType1.innerType.asInstanceOf[StructNameType].name == "Point")
        assert(managedType2.innerType.isInstanceOf[IntType])
      }
    }
    
    test("function parameters") {
      test("move parameters") {
        val code = """
          fn test(x: int, y: mut bool) -> unit {
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val funcDef = program.functions.head
        assert(funcDef.params.length == 2)
        
        val param1 = funcDef.params(0)
        val param2 = funcDef.params(1)
        
        assert(param1.name == "x")
        assert(param1.typ.isInstanceOf[IntType])
        assert(param1.mode == ParamMode.Move(false))
        
        assert(param2.name == "y")
        assert(param2.typ.isInstanceOf[BoolType])
        assert(param2.mode == ParamMode.Move(true))
      }
      
      test("ref parameter") {
        val code = """
          fn test(x: ref Point) -> unit {
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val funcDef = program.functions.head
        val param = funcDef.params.head
        
        assert(param.name == "x")
        assert(param.typ.asInstanceOf[StructNameType].name == "Point")
        assert(param.mode == ParamMode.Ref)
      }
      
      test("inout parameter") {
        val code = """
          fn test(x: inout Point) -> unit {
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val funcDef = program.functions.head
        val param = funcDef.params.head
        
        assert(param.name == "x")
        assert(param.typ.asInstanceOf[StructNameType].name == "Point")
        assert(param.mode == ParamMode.Inout)
      }
      
      test("managed parameter") {
        val code = """
          fn test(x: managed Point) -> unit {
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        val funcDef = program.functions.head
        val param = funcDef.params.head
        
        assert(param.name == "x")
        assert(param.typ.isInstanceOf[ManagedType])
        val managedType = param.typ.asInstanceOf[ManagedType]
        assert(managedType.innerType.asInstanceOf[StructNameType].name == "Point")
        assert(param.mode == ParamMode.Move(false))
      }
    }
    
    test("error handling") {
      test("invalid struct definition") {
        val e = intercept[ParserError] {
          val code = "struct { x: int }" // Missing struct name
          val parser = new LanguageParser(code)
          parser.parseProgram()
        }
        assert(e.getMessage.contains("Expected"))
      }
      
      test("missing semicolon") {
        val e = intercept[ParserError] {
          val code = """
            fn main() -> unit {
              let x = 42
            }
          """
          val parser = new LanguageParser(code)
          parser.parseProgram()
        }
        assert(e.getMessage.contains("Expected"))
      }
      
      test("invalid integer literal") {
        val e = intercept[ParserError] {
          val code = """
            fn main() -> unit {
              let x = 999999999999999999999999999999999999999999;
            }
          """
          val parser = new LanguageParser(code)
          parser.parseProgram()
        }
        assert(e.getMessage.contains("Invalid integer literal"))
      }
      
      test("unclosed struct literal") {
        val e = intercept[ParserError] {
          val code = """
            fn main() -> unit {
              let p = Point { x: 10;
            }
          """
          val parser = new LanguageParser(code)
          parser.parseProgram()
        }
        assert(e.getMessage.contains("Expected"))
      }
      
      test("invalid managed expression") {
        val e = intercept[ParserError] {
          val code = """
            fn main() -> unit {
              let x = managed 42;
            }
          """
          val parser = new LanguageParser(code)
          parser.parseProgram()
        }
        assert(e.getMessage.contains("Expected"))
      }
    }
    
    test("complex examples") {
      test("complete program") {
        val code = """
          struct Point {
            x: int,
            y: int
          }
          
          resource File {
            fd: int
            
            cleanup {
              close(fd);
            }
          }
          
          fn translate(pt: inout Point) -> unit {
            pt.x = 11;
            pt.y = 21;
          }
          
          fn main() -> unit {
            let mut p = Point { x: 10, y: 20 };
            translate(p);
            
            let managed_p = managed Point { x: 30, y: 40 };
            let shared_p = managed_p;
            
            println!("Point: {}, {}", p.x, p.y);
          }
        """
        val parser = new LanguageParser(code)
        val program = parser.parseProgram()
        
        assert(program.structs.length == 1)
        assert(program.resources.length == 1)
        assert(program.functions.length == 2)
        
        val structDef = program.structs.head
        assert(structDef.name == "Point")
        assert(structDef.fields.length == 2)
        
        val resourceDef = program.resources.head
        assert(resourceDef.name == "File")
        assert(resourceDef.cleanup.isDefined)
        
        val translateFunc = program.functions(0)
        assert(translateFunc.name == "translate")
        assert(translateFunc.params.length == 1)
        assert(translateFunc.params.head.mode == ParamMode.Inout)
        
        val mainFunc = program.functions(1)
        assert(mainFunc.name == "main")
        assert(mainFunc.params.isEmpty)
        assert(mainFunc.returnType.isInstanceOf[UnitType])
      }
    }
  }
}
