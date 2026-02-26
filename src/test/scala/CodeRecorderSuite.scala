import tacit.executor.{CodeRecorder, ExecutionResult}
import java.io.File
import java.nio.file.Files

class CodeRecorderSuite extends munit.FunSuite:

  def withTempDir(testName: String)(f: File => Unit): Unit =
    val dir = Files.createTempDirectory(s"recorder-test-$testName").toFile
    try f(dir)
    finally
      dir.listFiles().foreach(_.delete())
      dir.delete()

  private def scalaFiles(dir: File): Array[File] =
    dir.listFiles().filter(_.getName.endsWith(".scala")).sortBy(_.getName)

  private def resultFiles(dir: File): Array[File] =
    dir.listFiles().filter(_.getName.endsWith(".result")).sortBy(_.getName)

  test("creates directory and writes files"):
    withTempDir("creates") { parent =>
      val dir = File(parent, "logs")
      val recorder = CodeRecorder(dir)
      recorder.record("1 + 1", "s1", ExecutionResult(true, "val res0: Int = 2"))
      recorder.close()

      assert(dir.exists())
      assertEquals(scalaFiles(dir).length, 1)
      assertEquals(resultFiles(dir).length, 1)
    }

  test("code file contains session id in name and code in body"):
    withTempDir("code") { dir =>
      val recorder = CodeRecorder(dir)
      recorder.record("println(42)", "session-abc", ExecutionResult(true, "42"))
      recorder.close()

      val files = scalaFiles(dir)
      assertEquals(files.length, 1)
      assert(files.head.getName.contains("session-abc"))
      val content = scala.io.Source.fromFile(files.head).mkString
      assertEquals(content, "println(42)")
    }

  test("result file records success"):
    withTempDir("success") { dir =>
      val recorder = CodeRecorder(dir)
      recorder.record("1 + 1", "s1", ExecutionResult(true, "val res0: Int = 2"))
      recorder.close()

      val content = scala.io.Source.fromFile(resultFiles(dir).head).mkString
      assert(content.contains("status: success"))
      assert(content.contains("val res0: Int = 2"))
    }

  test("result file records failure with error"):
    withTempDir("failure") { dir =>
      val recorder = CodeRecorder(dir)
      recorder.record("bad code", "s2", ExecutionResult(false, "", Some("syntax error")))
      recorder.close()

      val content = scala.io.Source.fromFile(resultFiles(dir).head).mkString
      assert(content.contains("status: failure"))
      assert(content.contains("Error: syntax error"))
    }

  test("multiple records produce separate files"):
    withTempDir("multiple") { dir =>
      val recorder = CodeRecorder(dir)
      recorder.record("first", "s1", ExecutionResult(true, "out1"))
      recorder.record("second", "s2", ExecutionResult(true, "out2"))
      recorder.close()

      val codes = scalaFiles(dir)
      assertEquals(codes.length, 2)

      val contents = codes.map(f => scala.io.Source.fromFile(f).mkString).toSet
      assert(contents.contains("first"))
      assert(contents.contains("second"))

      val results = resultFiles(dir)
      assertEquals(results.length, 2)
      val resultContents = results.map(f => scala.io.Source.fromFile(f).mkString)
      assert(resultContents.exists(_.contains("out1")))
      assert(resultContents.exists(_.contains("out2")))
    }
