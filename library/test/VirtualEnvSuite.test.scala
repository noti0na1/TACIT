package library

import java.nio.file.{Files, Path}
import language.experimental.captureChecking

class VirtualEnvSuite extends munit.FunSuite:

  val interface: Interface = new InterfaceImpl((root, check) => new VirtualFileSystem(Path.of(root), check))

  import interface.*

  test("virtual: write and read back") {
    requestFileSystem("/virtual") {
      val file = access("/virtual/new.txt")
      file.write("new content")
      assertEquals(file.read(), "new content")
    }
  }

  test("virtual: append to file") {
    requestFileSystem("/virtual") {
      val file = access("/virtual/log.txt")
      file.write("line1\n")
      file.append("line2\n")
      assertEquals(file.read(), "line1\nline2\n")
    }
  }

  test("virtual: list directory") {
    requestFileSystem("/virtual") {
      access("/virtual/a.txt").write("a")
      access("/virtual/b.txt").write("b")
      val dir = access("/virtual")
      val kids = dir.children
      assertEquals(kids.length, 2)
      assert(kids.exists(_.name == "a.txt"))
      assert(kids.exists(_.name == "b.txt"))
    }
  }

  test("virtual: delete file") {
    requestFileSystem("/virtual") {
      val file = access("/virtual/doomed.txt")
      file.write("bye")
      assert(file.exists)
      file.delete()
      assert(!file.exists)
    }
  }

  test("virtual: readLines") {
    requestFileSystem("/virtual") {
      access("/virtual/lines.txt").write("a\nb\nc")
      val lines = access("/virtual/lines.txt").readLines()
      assertEquals(lines, List("a", "b", "c"))
    }
  }

  test("virtual: grep") {
    requestFileSystem("/virtual") {
      access("/virtual/data.txt").write("hello world\nfoo bar\nhello again")
      val matches = grep("/virtual/data.txt", "hello")
      assertEquals(matches.length, 2)
      assertEquals(matches(0).lineNumber, 1)
      assertEquals(matches(1).lineNumber, 3)
    }
  }

  test("virtual: find files by glob") {
    requestFileSystem("/virtual") {
      access("/virtual/a.scala").write("")
      access("/virtual/sub/b.scala").write("")
      access("/virtual/c.txt").write("")
      val found = find("/virtual", "*.scala")
      assertEquals(found.length, 2)
      assert(found.forall(_.endsWith(".scala")))
    }
  }

  test("virtual: walkDir") {
    requestFileSystem("/virtual") {
      access("/virtual/sub/file.txt").write("content")
      val entries = access("/virtual").walk()
      val dirs = entries.filter(_.isDirectory)
      val files = entries.filter(!_.isDirectory)
      assert(dirs.exists(_.name == "sub"))
      assert(files.exists(_.name == "file.txt"))
    }
  }

  test("virtual: reject path outside root") {
    requestFileSystem("/virtual") {
      val ex = intercept[SecurityException] {
        access("/etc/passwd")
      }
      assert(ex.getMessage.nn.startsWith("Access denied"))
    }
  }

  test("virtual: files don't touch real disk") {
    requestFileSystem("/virtual") {
      access("/virtual/ghost.txt").write("I don't exist on disk")
    }
    assert(!Files.exists(Path.of("/virtual/ghost.txt")))
  }
