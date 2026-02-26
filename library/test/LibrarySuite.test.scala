package tacit.library

import java.nio.file.{Files, Path}
import scala.compiletime.uninitialized
import language.experimental.captureChecking

class LibrarySuite extends munit.FunSuite:

  var tmpDir: Path = uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    tmpDir = Files.createTempDirectory("sandbox-test")

  val interface: Interface = new InterfaceImpl( (root, check, classified) => new RealFileSystem(Path.of(root), check, classified) )

  import interface.*

  given iocap: (IOCapability^{}) = null.asInstanceOf[IOCapability]

  override def afterEach(context: AfterEach): Unit =
    if Files.exists(tmpDir) then
      Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))

  test("read/write file round-trip within allowed root") {
    val filePath = tmpDir.resolve("hello.txt").toString
    requestFileSystem(tmpDir.toString) {
      val file = access(filePath)
      file.write("Hello, sandbox!")
      assertEquals(file.read(), "Hello, sandbox!")
    }
  }

  test("append to file") {
    val filePath = tmpDir.resolve("append.txt").toString
    requestFileSystem(tmpDir.toString) {
      val file = access(filePath)
      file.write("line1\n")
      file.append("line2\n")
      assertEquals(file.read(), "line1\nline2\n")
    }
  }

  test("list directory contents") {
    val aPath = tmpDir.resolve("a.txt").toString
    val bPath = tmpDir.resolve("b.txt").toString
    val dirPath = tmpDir.toString
    requestFileSystem(dirPath) {
      val fs = summon[FileSystem]
      fs.access(aPath).write("a")
      fs.access(bPath).write("b")
      val kids = fs.access(dirPath).children
      assertEquals(kids.length, 2)
      assert(kids.exists(_.name == "a.txt"))
      assert(kids.exists(_.name == "b.txt"))
    }
  }

  test("delete file") {
    val filePath = tmpDir.resolve("doomed.txt").toString
    requestFileSystem(tmpDir.toString) {
      val file = access(filePath)
      file.write("bye")
      assert(file.exists)
      file.delete()
      assert(!file.exists)
    }
  }

  test("reject path outside allowed roots") {
    requestFileSystem(tmpDir.toString) {
      val ex = intercept[SecurityException] {
        access("/etc/passwd")
      }
      assert(ex.getMessage.startsWith("Access denied"))
    }
  }

  test("readLines returns file as list of lines") {
    val filePath = tmpDir.resolve("lines.txt").toString
    requestFileSystem(tmpDir.toString) {
      val file = access(filePath)
      file.write("alpha\nbeta\ngamma")
      assertEquals(file.readLines(), List("alpha", "beta", "gamma"))
    }
  }

  test("grep finds pattern in file") {
    val filePath = tmpDir.resolve("data.txt").toString
    requestFileSystem(tmpDir.toString) {
      access(filePath).write("hello world\nfoo bar\nhello again")
      val matches = grep(filePath, "hello")
      assertEquals(matches.length, 2)
      assertEquals(matches(0).lineNumber, 1)
      assertEquals(matches(0).line, "hello world")
      assertEquals(matches(1).lineNumber, 3)
    }
  }

  test("grepRecursive searches across files with glob filter") {
    val dirPath = tmpDir.toString
    val aPath = tmpDir.resolve("a.scala").toString
    val bPath = tmpDir.resolve("sub/b.scala").toString
    val cPath = tmpDir.resolve("c.txt").toString
    requestFileSystem(dirPath) {
      val fs = summon[FileSystem]
      fs.access(aPath).write("val x = 1\nval y = 2")
      fs.access(bPath).write("val x = 10")
      fs.access(cPath).write("val x = ignored")
      val matches = grepRecursive(dirPath, "val x", "*.scala")
      assertEquals(matches.length, 2)
      assert(matches.forall(_.line.contains("val x")))
    }
  }

  test("find locates files by glob") {
    val dirPath = tmpDir.toString
    val aPath = tmpDir.resolve("one.scala").toString
    val bPath = tmpDir.resolve("sub/two.scala").toString
    val cPath = tmpDir.resolve("three.txt").toString
    requestFileSystem(dirPath) {
      val fs = summon[FileSystem]
      fs.access(aPath).write("")
      fs.access(bPath).write("")
      fs.access(cPath).write("")
      val found = find(dirPath, "*.scala")
      assertEquals(found.length, 2)
      assert(found.forall(_.endsWith(".scala")))
    }
  }

  test("walkDir lists entries recursively with metadata") {
    val dirPath = tmpDir.toString
    val filePath = tmpDir.resolve("dir1/file.txt").toString
    requestFileSystem(dirPath) {
      val fs = summon[FileSystem]
      fs.access(filePath).write("content")
      val entries = fs.access(dirPath).walk()
      val dirs = entries.filter(_.isDirectory)
      val files = entries.filter(!_.isDirectory)
      assert(dirs.exists(_.name == "dir1"))
      assert(files.exists(_.name == "file.txt"))
    }
  }

  test("exec runs allowed command and captures output") {
    requestExecPermission(Set("echo")) {
      val result = exec("echo", List("hello", "world"))
      assertEquals(result.exitCode, 0)
      assertEquals(result.stdout, "hello world")
    }
  }

  test("exec rejects disallowed command") {
    requestExecPermission(Set("echo")) {
      val ex = intercept[SecurityException] {
        exec("rm", List("-rf", "/"))
      }
      assert(ex.getMessage.nn.contains("Access denied"))
    }
  }
  test("classified path enforcement on real file system") {
    val secretDir = tmpDir.resolve("secret")
    Files.createDirectories(secretDir)
    val classifiedInterface: Interface = new InterfaceImpl(
      (root, check, classified) => new RealFileSystem(Path.of(root), check, classified),
      false,
      Set(secretDir)
    )
    import classifiedInterface.*

    requestFileSystem(tmpDir.toString) {
      // Normal file works
      val pub = access(tmpDir.resolve("public.txt").toString)
      pub.write("public data")
      assertEquals(pub.read(), "public data")
      assert(!pub.isClassified)

      // Classified file: normal ops blocked
      val sec = access(secretDir.resolve("data.txt").toString)
      assert(sec.isClassified)
      intercept[SecurityException] { sec.write("nope") }
      intercept[SecurityException] { sec.read() }

      // Classified ops work
      sec.writeClassified(classifiedInterface.classify("top-secret"))
      val content = sec.readClassified()
      assertEquals(content.toString, "Classified(***)")

      // readClassified on non-classified throws
      intercept[SecurityException] { pub.readClassified() }
    }
  }

  test("calling foreach(println) on the result of grepRecursive") {
    val dirPath = tmpDir.toString
    val matches = requestFileSystem(dirPath) {
      grepRecursive(dirPath, "line", "*.txt").map(m => s"Match in ${m.file}: ${m.line}")
    }
    // matches.foreach(println)

    val (projects, webappContents) = requestFileSystem(dirPath) {
      val projectsList = find(dirPath, "*")
      val webappList = find(dirPath, "*")
      (projectsList, webappList)
    }

    // println("Projects directory contents:")
    // projects.foreach(p => println(p))
    // println("\n---\n")
    // println("Webapp directory contents:")
    // webappContents.foreach(p => println(p))
  }

  // --- Compile-time capability leak examples ---
  // The following code would fail to compile with capture checking enabled,
  // because the capability `fs` cannot escape the scope of `requestFileSystem`.
  //
  //   var leaked: FileSystem = null
  //   requestFileSystem("/tmp") {
  //     leaked = summon[FileSystem]  // ERROR: local reference leaks into outer capture set
  //   }
  //
  // Similarly, storing the capability in a closure that escapes:
  //
  //   var escapedOp: () => String = null
  //   requestFileSystem("/tmp") {
  //     escapedOp = () => readFile("/tmp/secret.txt")
  //     // ERROR: the capability is captured but the closure type () => String
  //     // does not account for it in its capture set
  //   }
