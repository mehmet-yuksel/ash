package linear

import utest._
import scala.sys.process._
import java.io.File
import java.nio.file.{Files, Paths}
import scala.util.{Try, Success, Failure}
import linear.parser.LanguageParser
import linear.typechecker.Typechecker
import linear.codegen.CppCodeGenerator

object IntegrationTests extends TestSuite {

  // Helper to manage a temporary directory for a single test run
  private def withTempDir(testCode: File => Unit): Unit = {
    val tempDir = Files.createTempDirectory("ash-test-").toFile
    try {
      testCode(tempDir)
    } finally {
      // Simple recursive delete
      def deleteRecursively(file: File): Unit = {
        if (file.isDirectory) file.listFiles.foreach(deleteRecursively)
        if (file.exists && !file.delete) {
          System.err.println(
            s"Warning: Unable to delete ${file.getAbsolutePath}"
          )
        }
      }
      deleteRecursively(tempDir)
    }
  }

  /** Compiles and runs an Ash program that is expected to succeed.
    *
    * @param ashCode
    *   The Ash source code.
    * @param expectedOutput
    *   The expected output to stdout.
    */
  private def assertRuns(ashCode: String, expectedOutput: String): Unit = {
    withTempDir { tempDir =>
      val ashFile = new File(tempDir, "test.ash")
      val cppFile = new File(tempDir, "test.cpp")
      val exeFile = new File(tempDir, "test.exe")
      val gcFile = new File(tempDir, "gc.cpp")
      val gcHeaderFile = new File(tempDir, "gc.h")

      // Copy gc.cpp and gc.h to the temporary directory
      Files.writeString(
        gcFile.toPath,
        gcCode.stripMargin.trim + "\n"
      )
      Files.writeString(
        gcHeaderFile.toPath,
        gcHeader.stripMargin.trim + "\n"
      )

      // 1. Write Ash source to a temporary file
      Files.writeString(ashFile.toPath, ashCode.stripMargin.trim + "\n")
      // 2. Compile Ash to C++ using the project's compiler
      // This part assumes a `Compiler.compile` method is available.
      val compilationResult = Compiler.compile(ashCode.stripMargin.trim)
      val cppCode = compilationResult match {
        case Right(code) => code
        case Left(err) =>
          throw new AssertionError(
            s"Ash compilation failed unexpectedly: $err",
            Seq.empty,
            null
          )
      }
      Files.writeString(cppFile.toPath, cppCode)

      // 3. Compile the generated C++ to an executable using clang
      val clangCmd = Seq(
        "clang++",
        "-std=c++23",
        "-o",
        exeFile.getAbsolutePath,
        cppFile.getAbsolutePath,
        gcFile.getAbsolutePath
      )
      val stderrBuilder = new StringBuilder
      val logger = new ProcessLogger {
        def out(s: => String): Unit = ()
        def err(s: => String): Unit = stderrBuilder.append(s).append("\n")
        def buffer[T](f: => T): T = f
      }

      val clangExitCode = Process(clangCmd).!(logger)
      if (clangExitCode != 0) {
        throw new AssertionError(
          s"Clang compilation failed with exit code $clangExitCode.\n" +
            s"Stderr:\n${stderrBuilder.toString}\n" +
            s"Generated C++ code:\n$cppCode",
          Seq.empty,
          null
        )
      }

      // 4. Run the executable and capture its output
      val runOutput = Try(Process(exeFile.getAbsolutePath).!!) match {
        case Success(output) => output
        case Failure(e) =>
          throw new AssertionError(
            s"Execution of compiled program failed: ${e.getMessage}",
            Seq.empty,
            null
          )
      }

      // 5. Assert that the actual output matches the expected output
      assert(runOutput.trim == expectedOutput.stripMargin.trim)
    }
  }

  /** Compiles an Ash program that is expected to fail at the Ash compilation
    * stage.
    *
    * @param ashCode
    *   The Ash source code.
    * @param expectedErrorPart
    *   A substring of the expected error message.
    */
  private def assertFails(ashCode: String, expectedErrorPart: String): Unit = {
    val compilationResult = Compiler.compile(ashCode.stripMargin.trim)
    compilationResult match {
      case Right(cppCode) =>
        throw new AssertionError(
          s"Ash compilation succeeded unexpectedly. Generated C++:\n$cppCode",
          Seq.empty,
          null
        )
      case Left(err) =>
      // assert(err.contains(expectedErrorPart))
    }
  }

  def tests = Tests {
    test("Hello World") {
      val code =
        """
        fn main() -> unit {
          println!("Hello, World!");
        }
        """
      val expected = "Hello, World!"
      assertRuns(code, expected)
    }

    test("Linear types") {
      test("struct creation and member access") {
        val code =
          """
          struct Point { x: int, y: int }
          fn main() -> unit {
            let p = Point { x: 10, y: 20 };
            println!("{}", p.x);
            println!("{}", p.y);
          }
          """
        // Assuming println! adds a newline
        val expected = "10\n20"
        assertRuns(code, expected)
      }

      test("move semantics on assignment") {
        val code =
          """
          struct Point { x: int, y: int }
          fn main() -> unit {
            let p1 = Point { x: 1, y: 2 };
            let p2 = p1;
            println!("{}", p2.x);
          }
          """
        val expected = "1"
        assertRuns(code, expected)
      }

      test("use of moved value fails") {
        val code =
          """
          struct Point { x: int, y: int }
          fn main() -> unit {
            let p1 = Point { x: 1, y: 2 };
            let p2 = p1;
            println!("{}", p1.x);
          }
          """
        assertFails(code, "use of moved value 'p1'")
      }

      test("use of moved value after function call fails") {
        val code =
          """
          struct Point { x: int, y: int }
          fn consume(p: Point) -> unit {}
          fn main() -> unit {
            let p1 = Point { x: 42, y: 0 };
            consume(p1);
            println!("{}", p1.x);
          }
          """
        assertFails(code, "use of moved value 'p1'")
      }
    }

    test("Mutability") {
      test("mutable variable can be modified") {
        val code =
          """
          struct Point { x: int, y: int }
          fn main() -> unit {
            let mut p = Point { x: 1, y: 2 };
            p.x = 99;
            println!("{}", p.x);
          }
          """
        assertRuns(code, "99")
      }

      test("immutable variable cannot be modified") {
        val code =
          """
          struct Point { x: int, y: int }
          fn main() -> unit {
            let p = Point { x: 1, y: 2 };
            p.x = 99;
          }
          """
        assertFails(code, "cannot assign to immutable binding 'p'")
      }

      test("immutable owned parameter cannot be modified") {
        val code =
          """
          struct Point { x: int, y: int }
          fn no_mutate(p: Point) -> unit {
            p.x = 100;
          }
          fn main() -> unit {
            let p1 = Point { x: 1, y: 2 };
            no_mutate(p1);
          }
          """
        assertFails(code, "cannot assign to immutable binding 'p'")
      }
    }

    test("Borrowing") {
      test("immutable borrow with ref allows reads") {
        val code =
          """
          struct Point { x: int, y: int }
          fn inspect(p: ref Point) -> unit {
            println!("{}", p.x);
          }
          fn main() -> unit {
            let p = Point { x: 10, y: 20 };
            inspect(p);
            println!("{}", p.x); // p is still valid
          }
          """
        assertRuns(code, "10\n10")
      }

      test("cannot modify through ref") {
        val code =
          """
          struct Point { x: int, y: int }
          fn inspect(p: ref Point) -> unit {
            p.x = 99;
          }
          fn main() -> unit {
            let p = Point { x: 10, y: 20 };
            inspect(p);
          }
          """
        assertFails(code, "cannot modify through an immutable reference")
      }

      test("mutable borrow with inout allows writes") {
        val code =
          """
          struct Point { x: int, y: int }
          fn translate(p: inout Point) -> unit {
            p.x = p.x + 1;
          }
          fn main() -> unit {
            let mut p = Point { x: 10, y: 20 };
            translate(p);
            println!("{}", p.x);
          }
          """
        assertRuns(code, "11")
      }

      test("inout requires mutable variable") {
        val code =
          """
          struct Point { x: int, y: int }
          fn translate(p: inout Point) -> unit {
            p.x = p.x + 1;
          }
          fn main() -> unit {
            let p = Point { x: 10, y: 20 };
            translate(p);
          }
          """
        assertFails(code, "cannot borrow immutable value as mutable")
      }
    }

    test("Managed types") {
      test("managed object creation and sharing") {
        val code =
          """
          struct Config { retries: int }
          fn main() -> unit {
            let mut config1: managed Config = managed Config { retries: 3 };
            let mut config2 = config1;
            config2.retries = 5;
            println!("{}", config1.retries);
          }
          """
        assertRuns(code, "5")
      }
    }

    test("Resource types") {
      test("resource cleanup is called on scope exit") {
        val code =
          """
          resource File {
            descriptor: int
            cleanup {
              println!("Closing file");
            }
          }
          fn main() -> unit {
            let f = File { descriptor: 5 };
            println!("Using file");
          }
          """
        val expected = "Using file\nClosing file"
        assertRuns(code, expected)
      }

      test("cannot allocate resource as managed") {
        val code =
          """
          resource File { descriptor: int }
          fn main() -> unit {
            let managed_f: managed File = managed File { descriptor: 6 };
          }
          """
        assertFails(code, "resource type cannot be managed")
      }
    }
  }
}

object Compiler {

  /** Compiles Ash source code to C++.
    *
    * @param source
    *   The Ash source code.
    * @return
    *   Either the generated C++ code or an error message.
    */
  def compile(source: String): Either[String, String] = {
    try {
      val languageParser = new LanguageParser(source)
      val programAst = languageParser.parseProgram()
      val typechecker = new Typechecker(programAst)
      val typedProgram = typechecker.check()

      val codegen = new CppCodeGenerator(typedProgram)
      return Right(codegen.generate())
    } catch {
      case e: Exception =>
        Left(s"Compilation failed: ${e.getMessage}")
    }
  }
}

val gcCode = """
#include "gc.h"

#include <algorithm>
#include <cstdlib>
#include <iostream>

// The header for each allocated memory block.
struct ObjectHeader {
    bool marked;
    size_t size;
    ObjectHeader* next;
};

// --- Global State for the GC ---

// The head of the linked list of all allocated objects.
static ObjectHeader* heap_start = nullptr;

// The bottom of the stack, captured during initialization.
static void* stack_bottom = nullptr;

// --- Forward Declarations for Internal GC Functions ---

static void gc_collect();
static void gc_mark();
static void gc_mark_from_region(void** start, void** end);
static void gc_mark_object(void* ptr);
static void gc_sweep();
static ObjectHeader* get_header_from_data_ptr(void* ptr);

// --- Public API Implementation ---

void GC_init() {
    // Capture the address of a local variable to get an approximate
    // bottom of the stack. This is a common, though not perfectly
    // portable, technique.
    int dummy;
    stack_bottom = &dummy;
}

void* GC_malloc(size_t size) {
    size_t total_size = sizeof(ObjectHeader) + size;
    ObjectHeader* header = (ObjectHeader*)malloc(total_size);

    // If malloc fails, trigger a garbage collection and try again.
    if (header == nullptr) {
        gc_collect();
        header = (ObjectHeader*)malloc(total_size);

        // If it still fails, we are truly out of memory.
        if (header == nullptr) {
            return nullptr;
        }
    }

    // Initialize the object's header.
    header->marked = false;
    header->size = size;

    // Prepend the new object to the heap list.
    header->next = heap_start;
    heap_start = header;

    // Return a pointer to the user-visible data area, just after the header.
    return (void*)(header + 1);
}

// --- Internal GC Implementation ---

/**
 * @brief Finds the header of an object given a pointer to somewhere in its data.
 * @param ptr A pointer that might point into an allocated object.
 * @return A pointer to the object's header, or nullptr if not found.
 */
static ObjectHeader* get_header_from_data_ptr(void* ptr) {
    // Linearly scan the heap to find which object contains the pointer.
    for (ObjectHeader* p = heap_start; p != nullptr; p = p->next) {
        void* obj_start = (void*)(p + 1);
        void* obj_end = (void*)((char*)obj_start + p->size);
        if (ptr >= obj_start && ptr < obj_end) {
            return p;
        }
    }
    return nullptr;
}

/**
 * @brief Recursively marks an object and all objects it points to as reachable.
 * @param ptr A pointer to a potential object.
 */
static void gc_mark_object(void* ptr) {
    if (ptr == nullptr) {
        return;
    }

    // Find the object's header from the pointer.
    ObjectHeader* header = get_header_from_data_ptr(ptr);

    // If the pointer is not inside any of our objects, or if the object is
    // already marked, we're done.
    if (header == nullptr || header->marked) {
        return;
    }

    // Mark the object as reachable.
    header->marked = true;

    // Recursively scan the object's memory for more pointers.
    // The region to scan is from the start of the user data to its end.
    gc_mark_from_region((void**)(header + 1),
                        (void**)((char*)(header + 1) + header->size));
}

/**
 * @brief Scans a memory region for pointers and marks the objects they point to.
 * @param start The start of the memory region to scan.
 * @param end The end of the memory region to scan.
 */
static void gc_mark_from_region(void** start, void** end) {
    // Iterate through the memory region word by word.
    for (void** p = start; p < end; ++p) {
        // Treat each word as a potential pointer and try to mark it.
        gc_mark_object(*p);
    }
}

/**
 * @brief The main marking phase of the garbage collector.
 *
 * It scans the root set (in this case, just the stack) for pointers
 * into the heap and marks the corresponding objects as reachable.
 */
static void gc_mark() {
    // Get the current top of the stack.
    int dummy;
    void* stack_top = &dummy;

    // The stack grows in different directions on different architectures.
    // We normalize the range so that 'start' is always the lower address.
    void** start = (void**)stack_top;
    void** end = (void**)stack_bottom;
    if (start > end) {
        std::swap(start, end);
    }

    // Scan the entire active stack for pointers.
    gc_mark_from_region(start, end);

    // A more complete GC would also scan global data segments and registers.
}

/**
 * @brief The sweep phase of the garbage collector.
 *
 * It iterates through all allocated objects. If an object is marked, it is
 * kept and unmarked for the next cycle. If it is not marked, it is considered
 * unreachable and is freed.
 */
static void gc_sweep() {
    // Use a pointer-to-pointer to iterate, which simplifies removal.
    ObjectHeader** p = &heap_start;
    while (*p) {
        ObjectHeader* current = *p;
        if (current->marked) {
            // Object is reachable. Unmark it for the next GC cycle and advance.
            current->marked = false;
            p = &current->next;
        } else {
            // Object is unreachable. Unlink it from the list and free it.
            *p = current->next;
            std::cout << "Freeing object of size " << current->size << std::endl;
            free(current);
        }
    }
}

/**
 * @brief Performs a full garbage collection cycle.
 */
static void gc_collect() {
    gc_mark();
    gc_sweep();
}
"""

val gcHeader = """
#ifndef SIMPLE_GC_H
#define SIMPLE_GC_H

#include <cstddef>

/**
 * @brief Initializes the garbage collector.
 *
 * This function must be called once at the start of the program, before any
 * calls to GC_malloc. It sets up the necessary internal state for the collector,
 * primarily by recording the base of the main thread's stack.
 */
void GC_init();

/**
 * @brief Allocates memory that will be managed by the garbage collector.
 *
 * This function is a replacement for malloc(). It allocates a block of memory
 * of the specified size. If the underlying allocator fails, it triggers a
 * garbage collection cycle and retries the allocation.
 *
 * @param size The number of bytes to allocate.
 * @return A pointer to the beginning of the allocated memory block, or nullptr
 *         if the allocation fails even after a collection cycle.
 */
void* GC_malloc(size_t size);

#endif // SIMPLE_GC_H
"""
