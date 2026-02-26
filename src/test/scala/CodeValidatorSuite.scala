import tacit.executor.{CodeValidator, ScalaExecutor, SessionManager, ValidationViolation}
import tacit.core.{Context, Config}

class CodeValidatorSuite extends munit.FunSuite:
  given Context = Context(Config(), None)

  // ---- Rejection tests by category ----

  test("reject java.io"):
    val result = CodeValidator.validate("import java.io.File")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "file-io-java")))

  test("reject java.nio"):
    val result = CodeValidator.validate("import java.nio.file.Files")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "file-io-nio")))

  test("reject scala.io"):
    val result = CodeValidator.validate("import scala.io.Source")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "file-io-scala")))

  test("reject ProcessBuilder"):
    val result = CodeValidator.validate("new ProcessBuilder(\"ls\").start()")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "proc-builder")))

  test("reject Runtime.getRuntime"):
    val result = CodeValidator.validate("Runtime.getRuntime.exec(\"ls\")")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "proc-runtime")))

  test("reject scala.sys.process"):
    val result = CodeValidator.validate("import scala.sys.process._")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "proc-scala")))

  test("reject java.net"):
    val result = CodeValidator.validate("import java.net.URL")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "net-java")))

  test("reject javax.net"):
    val result = CodeValidator.validate("import javax.net.ssl.SSLContext")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "net-javax")))

  test("reject HttpClient"):
    val result = CodeValidator.validate("val c = HttpClient.newHttpClient()")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "net-http-client")))

  test("reject HttpURLConnection"):
    val result = CodeValidator.validate("val c: HttpURLConnection = ???")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "net-http-conn")))

  test("reject .asInstanceOf"):
    val result = CodeValidator.validate("x.asInstanceOf[String]")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "cast-escape")))

  test("reject caps.unsafe"):
    val result = CodeValidator.validate("import caps.unsafe.given")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "cc-unsafe-caps")))

  test("reject unsafeAssumePure"):
    val result = CodeValidator.validate("val x = unsafeAssumePure(ref)")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "cc-unsafe-pure")))

  test("reject getDeclaredMethod"):
    val result = CodeValidator.validate("cls.getDeclaredMethod(\"foo\")")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "reflect-method")))

  test("reject getDeclaredField"):
    val result = CodeValidator.validate("cls.getDeclaredField(\"bar\")")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "reflect-field")))

  test("reject getDeclaredConstructor"):
    val result = CodeValidator.validate("cls.getDeclaredConstructor()")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "reflect-ctor")))

  test("reject setAccessible"):
    val result = CodeValidator.validate("field.setAccessible(true)")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "reflect-accessible")))

  test("reject java.lang.reflect"):
    val result = CodeValidator.validate("import java.lang.reflect.Method")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "reflect-java")))

  test("reject scala.reflect.runtime"):
    val result = CodeValidator.validate("import scala.reflect.runtime.universe._")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "reflect-scala")))

  test("reject Class.forName"):
    val result = CodeValidator.validate("Class.forName(\"java.lang.String\")")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "reflect-forname")))

  test("reject sun.misc"):
    val result = CodeValidator.validate("import sun.misc.Unsafe")
    assert(result.isLeft)

  test("reject jdk.internal"):
    val result = CodeValidator.validate("import jdk.internal.misc.Unsafe")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "jvm-jdk-internal")))

  test("reject com.sun.*"):
    val result = CodeValidator.validate("import com.sun.net.httpserver.HttpServer")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "jvm-com-sun")))

  test("reject System.out"):
    val result = CodeValidator.validate("System.out.println(\"hello\")")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "io-system-out")))

  test("reject System.err"):
    val result = CodeValidator.validate("System.err.println(\"hello\")")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "io-system-err")))

  test("reject Console"):
    val result = CodeValidator.validate("Console.println(\"hello\")")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "io-console")))

  test("reject Predef.print"):
    val result = CodeValidator.validate("scala.Predef.println(\"hello\")")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "io-predef-print")))

  test("allow System.out in string literal"):
    val result = CodeValidator.validate("""println("System.out is just text")""")
    assert(result.isRight)

  test("allow Console in string literal"):
    val result = CodeValidator.validate("""println("Console is just text")""")
    assert(result.isRight)

  test("reject System.exit"):
    val result = CodeValidator.validate("System.exit(0)")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "sys-exit")))

  test("reject System.setProperty"):
    val result = CodeValidator.validate("System.setProperty(\"foo\", \"bar\")")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "sys-setprop")))

  test("reject System.getenv"):
    val result = CodeValidator.validate("System.getenv(\"PATH\")")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "sys-getenv")))

  test("reject System.getProperty"):
    val result = CodeValidator.validate("System.getProperty(\"user.home\")")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "sys-getprop")))

  test("reject new Thread"):
    val result = CodeValidator.validate("new Thread(() => println(\"hi\")).start()")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "sys-thread")))

  test("reject //> using directive"):
    val result = CodeValidator.validate("//> using dep \"com.lihaoyi::os-lib:0.9.1\"")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "directive-using")))

  test("reject import $ directive"):
    val result = CodeValidator.validate("import $ivy.`com.lihaoyi::os-lib:0.9.1`")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "directive-import")))

  test("reject ClassLoader"):
    val result = CodeValidator.validate("Thread.currentThread().getContextClassLoader")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "classloader")))

  test("reject URLClassLoader"):
    val result = CodeValidator.validate("new URLClassLoader(Array())")
    assert(result.isLeft)

  test("reject dotty.tools"):
    val result = CodeValidator.validate("import dotty.tools.repl.ReplDriver")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "dotty-tools")))

  test("reject scala.tools"):
    val result = CodeValidator.validate("import scala.tools.nsc.Global")
    assert(result.isLeft)
    assert(result.left.exists(_.exists(_.ruleId == "scala-tools")))

  // ---- Allowlist tests ----

  test("allow simple arithmetic"):
    val result = CodeValidator.validate("1 + 1")
    assert(result.isRight)

  test("allow val/def definitions"):
    val result = CodeValidator.validate("""
      val x = 42
      def add(a: Int, b: Int): Int = a + b
      add(x, 1)
    """)
    assert(result.isRight)

  test("allow java.time"):
    val result = CodeValidator.validate("import java.time.LocalDate")
    assert(result.isRight)

  test("allow java.util"):
    val result = CodeValidator.validate("import java.util.ArrayList")
    assert(result.isRight)

  test("allow scala.collection"):
    val result = CodeValidator.validate("import scala.collection.mutable.ListBuffer")
    assert(result.isRight)

  test("allow scala.util.Try"):
    val result = CodeValidator.validate("import scala.util.Try")
    assert(result.isRight)

  test("allow List/Map/Set usage"):
    val result = CodeValidator.validate("""
      val xs = List(1, 2, 3).map(_ * 2)
      val m = Map("a" -> 1)
      val s = Set(1, 2, 3)
    """)
    assert(result.isRight)

  // ---- String/comment stripping tests ----

  test("allow forbidden pattern inside double-quoted string"):
    val result = CodeValidator.validate("""println("java.io is blocked")""")
    assert(result.isRight)

  test("allow forbidden pattern inside triple-quoted string"):
    val code = "val s = \"\"\"java.io.File is mentioned here\"\"\""
    val result = CodeValidator.validate(code)
    assert(result.isRight)

  test("allow forbidden pattern inside line comment"):
    val result = CodeValidator.validate("val x = 1 // java.io.File is fine here")
    assert(result.isRight)

  test("allow forbidden pattern inside block comment"):
    val result = CodeValidator.validate("val x = 1 /* java.io.File */ + 2")
    assert(result.isRight)

  test("reject forbidden pattern on code line even if comment exists on another line"):
    val code = """
      // This is a comment about java.io
      import java.io.File
    """
    val result = CodeValidator.validate(code)
    assert(result.isLeft)

  // ---- Strip function unit tests ----

  test("stripLiteralsAndComments preserves line count"):
    val code = "line1\nline2\nline3"
    val stripped = CodeValidator.stripLiteralsAndComments(code)
    assertEquals(stripped.count(_ == '\n'), code.count(_ == '\n'))

  test("stripLiteralsAndComments blanks string content"):
    val code = """val s = "java.io.File""""
    val stripped = CodeValidator.stripLiteralsAndComments(code)
    assert(!stripped.contains("java.io"))

  test("stripLiteralsAndComments blanks block comment"):
    val code = "val x = /* java.io.File */ 1"
    val stripped = CodeValidator.stripLiteralsAndComments(code)
    assert(!stripped.contains("java.io"))

  test("stripLiteralsAndComments blanks line comment"):
    val code = "val x = 1 // java.io.File"
    val stripped = CodeValidator.stripLiteralsAndComments(code)
    assert(!stripped.contains("java.io"))

  test("stripLiteralsAndComments handles escape sequences"):
    val code = """val s = "foo\"bar java.io" """
    val stripped = CodeValidator.stripLiteralsAndComments(code)
    assert(!stripped.contains("java.io"))

  // ---- Violation details ----

  test("violation includes correct line number"):
    val code = "val x = 1\nimport java.io.File\nval y = 2"
    val result = CodeValidator.validate(code)
    assert(result.isLeft)
    val violations = result.left.getOrElse(Nil)
    assert(violations.exists(_.lineNumber == 2))

  test("multiple violations reported"):
    val code = "import java.io.File\nimport java.net.URL"
    val result = CodeValidator.validate(code)
    assert(result.isLeft)
    val violations = result.left.getOrElse(Nil)
    assert(violations.size >= 2)

  test("formatErrors produces readable output"):
    val violations = List(
      ValidationViolation("file-io-java", "Direct java.io access is forbidden", 1, "import java.io.File")
    )
    val output = CodeValidator.formatErrors(violations)
    assert(output.contains("1 violation"))
    assert(output.contains("file-io-java"))
    assert(output.contains("Line 1"))

  // ---- Integration tests ----

  test("ScalaExecutor.execute rejects java.io.File"):
    val result = ScalaExecutor.execute("import java.io.File")
    assert(!result.success)
    assert(result.error.exists(_.contains("file-io-java")))

  test("ScalaExecutor.execute allows simple expression"):
    val result = ScalaExecutor.execute("1 + 1")
    assert(result.success)
    assert(result.output.contains("2"))

  test("ScalaExecutor.execute allows pattern in string literal"):
    val result = ScalaExecutor.execute("""println("java.io is just a string")""")
    assert(result.success)
    assert(result.output.contains("java.io is just a string"))

  test("SessionManager rejects forbidden code"):
    val sm = new SessionManager
    val sid = sm.createSession()
    val result = sm.executeInSession(sid, "import java.io.File")
    assert(result.isRight) // Right(ExecutionResult) from executeInSession
    assert(!result.toOption.get.success)
    assert(result.toOption.get.error.exists(_.contains("file-io-java")))
    sm.deleteSession(sid)
