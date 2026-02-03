import executor.ScalaExecutor

class ScalaExecutorSuite extends munit.FunSuite:

  test("execute simple expression"):
    val result = ScalaExecutor.execute("1 + 1")
    assert(result.success)
    assert(result.output.contains("2"))

  test("execute println"):
    val result = ScalaExecutor.execute("""println("Hello, World!")""")
    assert(result.success)
    assert(result.output.contains("Hello, World!"))

  test("execute val definition"):
    val result = ScalaExecutor.execute("val x = 42")
    assert(result.success)
    assert(result.output.contains("42"))

  test("execute function definition and call"):
    val result = ScalaExecutor.execute("""
      def add(a: Int, b: Int): Int = a + b
      add(2, 3)
    """)
    assert(result.success)
    assert(result.output.contains("5"))

  test("handle syntax error"):
    val result = ScalaExecutor.execute("val x = ")
    // Should handle gracefully - either success=false or contains error in output
    assert(!result.success || result.output.toLowerCase.contains("error"))

  test("use scala.collection.List"):
    val result = ScalaExecutor.execute("List(1, 2, 3).map(_ * 2)")
    assert(result.success)
    assert(result.output.contains("List(2, 4, 6)"))

  test("use scala.collection.Map"):
    val result = ScalaExecutor.execute("""Map("a" -> 1, "b" -> 2).values.toList.sorted""")
    assert(result.success)
    assert(result.output.contains("List(1, 2)"))

  test("reject java.io.File"):
    val result = ScalaExecutor.execute("""
      import java.io.File
      val f = new File("/tmp")
      f.isDirectory
    """)
    assert(!result.success)
    assert(result.error.exists(_.contains("file-io-java")))

  test("use java.time"):
    val result = ScalaExecutor.execute("""
      import java.time.LocalDate
      val d = LocalDate.of(2025, 1, 1)
      d.getYear
    """)
    assert(result.success)
    assert(result.output.contains("2025"))

  test("use scala.util.Try"):
    val result = ScalaExecutor.execute("""
      import scala.util.Try
      Try("123".toInt).isSuccess
    """)
    assert(result.success)
    assert(result.output.contains("true"))

  test("reject scala.io.Source"):
    val result = ScalaExecutor.execute("""
      import scala.io.Source
      Source.fromString("hello\nworld").getLines().toList
    """)
    assert(!result.success)
    assert(result.error.exists(_.contains("file-io-scala")))
