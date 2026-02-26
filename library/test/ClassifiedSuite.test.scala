package tacit.library

import java.nio.file.Path
import language.experimental.captureChecking

class ClassifiedSuite extends munit.FunSuite:

  given iocap: (IOCapability^{}) = null.asInstanceOf[IOCapability]

  // ── Classified[T] unit tests ──────────────────────────────────────────

  test("Classified.apply creates a classified value") {
    val c = ClassifiedImpl.wrap("secret")
    assert(c != null)
  }

  test("Classified.toString hides content") {
    val c = ClassifiedImpl.wrap("secret-password")
    assertEquals(c.toString, "Classified(***)")
  }

  test("Classified.map transforms with pure function") {
    val c = ClassifiedImpl.wrap("hello")
    val upper = c.map(_.toUpperCase)
    assertEquals(upper.toString, "Classified(***)")
  }

  test("Classified.flatMap chains classified operations") {
    val c = ClassifiedImpl.wrap(42)
    val result = c.flatMap(x => ClassifiedImpl.wrap(x * 2))
    assertEquals(result.toString, "Classified(***)")
  }

  // ── File system classified path enforcement (VirtualFileSystem) ───────

  val classifiedDir = Path.of("/virtual/secret").toAbsolutePath.normalize

  val interface: Interface = new InterfaceImpl(
    (root, check, classified) => new VirtualFileSystem(Path.of(root), check, classifiedPaths = classified),
    false,
    Set(classifiedDir)
  )

  import interface.*

  test("isClassified returns true for classified file") {
    requestFileSystem("/virtual") {
      val file = access("/virtual/secret/data.txt")
      assert(file.isClassified)
    }
  }

  test("isClassified returns false for non-classified file") {
    requestFileSystem("/virtual") {
      val file = access("/virtual/public.txt")
      assert(!file.isClassified)
    }
  }

  test("read() on classified file throws SecurityException") {
    requestFileSystem("/virtual") {
      val file = access("/virtual/secret/data.txt")
      val ex = intercept[SecurityException] { file.read() }
      assert(ex.getMessage.nn.contains("classified"))
    }
  }

  test("write() on classified file throws SecurityException") {
    requestFileSystem("/virtual") {
      val file = access("/virtual/secret/data.txt")
      val ex = intercept[SecurityException] { file.write("nope") }
      assert(ex.getMessage.nn.contains("classified"))
    }
  }

  test("readBytes() on classified file throws SecurityException") {
    requestFileSystem("/virtual") {
      val file = access("/virtual/secret/data.txt")
      val ex = intercept[SecurityException] { file.readBytes() }
      assert(ex.getMessage.nn.contains("classified"))
    }
  }

  test("readLines() on classified file throws SecurityException") {
    requestFileSystem("/virtual") {
      val file = access("/virtual/secret/data.txt")
      val ex = intercept[SecurityException] { file.readLines() }
      assert(ex.getMessage.nn.contains("classified"))
    }
  }

  test("append() on classified file throws SecurityException") {
    requestFileSystem("/virtual") {
      val file = access("/virtual/secret/data.txt")
      val ex = intercept[SecurityException] { file.append("nope") }
      assert(ex.getMessage.nn.contains("classified"))
    }
  }

  test("delete() on classified file throws SecurityException") {
    requestFileSystem("/virtual") {
      val file = access("/virtual/secret/data.txt")
      val ex = intercept[SecurityException] { file.delete() }
      assert(ex.getMessage.nn.contains("classified"))
    }
  }

  test("children on classified directory throws SecurityException") {
    requestFileSystem("/virtual") {
      val dir = access("/virtual/secret")
      val ex = intercept[SecurityException] { dir.children }
      assert(ex.getMessage.nn.contains("classified"))
    }
  }

  test("walk() on classified directory throws SecurityException") {
    requestFileSystem("/virtual") {
      val dir = access("/virtual/secret")
      val ex = intercept[SecurityException] { dir.walk() }
      assert(ex.getMessage.nn.contains("classified"))
    }
  }

  test("writeClassified() writes content to classified file") {
    requestFileSystem("/virtual") {
      val file = access("/virtual/secret/data.txt")
      file.writeClassified(classify("top-secret"))
      val content = file.readClassified()
      assertEquals(content.toString, "Classified(***)")
    }
  }

  test("readClassified() on classified file returns Classified[String]") {
    requestFileSystem("/virtual") {
      access("/virtual/secret/data.txt").writeClassified(classify("secret-content"))
      val content = access("/virtual/secret/data.txt").readClassified()
      assertEquals(content.toString, "Classified(***)")
      // Verify content via map
      val upper = content.map(_.toUpperCase)
      assertEquals(upper.toString, "Classified(***)")
    }
  }

  test("readClassified() on non-classified file throws SecurityException") {
    requestFileSystem("/virtual") {
      access("/virtual/public.txt").write("public data")
      val file = access("/virtual/public.txt")
      val ex = intercept[SecurityException] { file.readClassified() }
      assert(ex.getMessage.nn.contains("not classified"))
    }
  }

  test("writeClassified() on non-classified file throws SecurityException") {
    requestFileSystem("/virtual") {
      val file = access("/virtual/public.txt")
      val ex = intercept[SecurityException] { file.writeClassified(classify("data")) }
      assert(ex.getMessage.nn.contains("not classified"))
    }
  }

  test("convenience readClassified/writeClassified on Interface") {
    requestFileSystem("/virtual") {
      writeClassified("/virtual/secret/conv.txt", classify("interface-level"))
      val content = readClassified("/virtual/secret/conv.txt")
      assertEquals(content.toString, "Classified(***)")
    }
  }

  test("round-trip: write classified, read classified, map, write classified") {
    requestFileSystem("/virtual") {
      // Write initial secret
      val secret = classify("original-secret")
      access("/virtual/secret/round.txt").writeClassified(secret)
      // Read it back
      val read1 = access("/virtual/secret/round.txt").readClassified()
      // Transform it
      val transformed = read1.map(s => s"processed: $s")
      // Write transformed version
      access("/virtual/secret/round2.txt").writeClassified(transformed)
      // Read final version
      val read2 = access("/virtual/secret/round2.txt").readClassified()
      assertEquals(read2.toString, "Classified(***)")
      // Verify content through map
      val check = read2.map(_.startsWith("processed:"))
      assertEquals(check.toString, "Classified(***)")
    }
  }

  test("metadata operations work on classified files") {
    requestFileSystem("/virtual") {
      val file = access("/virtual/secret/meta.txt")
      // exists, isDirectory, size, path, name should all work
      assertEquals(file.exists, false)
      assertEquals(file.isDirectory, false)
      assertEquals(file.name, "meta.txt")
      assert(file.path.endsWith("meta.txt"))
      // Write and check size
      file.writeClassified(classify("hello"))
      assertEquals(file.exists, true)
      assertEquals(file.size, 5L)
    }
  }
