import executor.{ScalaExecutor, SessionManager}
import core.Context

class LibraryIntegrationSuite extends munit.FunSuite:
  given Context = Context(None, strictMode = false)

  /** Helper: assert that code fails to compile in the REPL with an error matching `pattern`. */
  private def assertCompileError(code: String, pattern: String)(using loc: munit.Location): Unit =
    val result = ScalaExecutor.execute(code)
    val output = result.output.toLowerCase
    assert(output.contains("error"), s"expected a compile error, got: ${result.output}")
    assert(output.contains(pattern.toLowerCase), s"expected error containing '$pattern', got: ${result.output}")

  test("requestFileSystem and access a path"):
    val result = ScalaExecutor.execute("""
      requestFileSystem("/tmp") { access("/tmp").exists }
    """)
    assert(result.success, s"execution failed: ${result.error.getOrElse(result.output)}")
    assert(result.output.contains("true"), s"unexpected output: ${result.output}")

  test("requestFileSystem and read directory children"):
    val result = ScalaExecutor.execute("""
      requestFileSystem("/tmp") { access("/tmp").isDirectory }
    """)
    assert(result.success, s"execution failed: ${result.error.getOrElse(result.output)}")
    assert(result.output.contains("true"), s"unexpected output: ${result.output}")

  test("requestFileSystem write and read back"):
    val result = ScalaExecutor.execute("""
      requestFileSystem("/tmp") {
        val f = access("/tmp/safe-exec-mcp-test.txt")
        f.write("hello from test")
        val content = f.read()
        f.delete()
        content
      }
    """)
    assert(result.success, s"execution failed: ${result.error.getOrElse(result.output)}")
    assert(result.output.contains("hello from test"), s"unexpected output: ${result.output}")

  test("requestExecPermission and exec a command"):
    val result = ScalaExecutor.execute("""
      requestExecPermission(Set("echo")) {
        exec("echo", List("hello")).stdout.trim
      }
    """)
    assert(result.success, s"execution failed: ${result.error.getOrElse(result.output)}")
    assert(result.output.contains("hello"), s"unexpected output: ${result.output}")

  test("requestExecPermission and execOutput"):
    val result = ScalaExecutor.execute("""
      requestExecPermission(Set("echo")) {
        execOutput("echo", List("world"))
      }
    """)
    assert(result.success, s"execution failed: ${result.error.getOrElse(result.output)}")
    assert(result.output.contains("world"), s"unexpected output: ${result.output}")

  test("grep in filesystem"):
    val result = ScalaExecutor.execute("""
      requestFileSystem("/tmp") {
        val f = access("/tmp/safe-exec-mcp-grep-test.txt")
        f.write("line one\nfind me here\nline three")
        val matches = grep("/tmp/safe-exec-mcp-grep-test.txt", "find me")
        f.delete()
        matches.map(_.line)
      }
    """)
    assert(result.success, s"execution failed: ${result.error.getOrElse(result.output)}")
    assert(result.output.contains("find me here"), s"unexpected output: ${result.output}")

  test("library available in session"):
    val manager = new SessionManager
    val sessionId = manager.createSession()

    val r1 = manager.executeInSession(sessionId, """
      requestFileSystem("/tmp") { access("/tmp").exists }
    """)
    assert(r1.isRight, s"session execution failed: $r1")
    assert(r1.toOption.get.output.contains("true"), s"unexpected output: ${r1.toOption.get.output}")

    manager.deleteSession(sessionId)

  // ── Negative tests: capture checking prevents capability leaks ──

  test("cannot leak FileEntry out of requestFileSystem"):
    assertCompileError(
      """val leaked = requestFileSystem("/tmp") { access("/tmp") }""",
      "leaks into outer capture set"
    )

  test("cannot leak closure capturing FileSystem"):
    assertCompileError(
      """val fn = requestFileSystem("/tmp") { () => access("/tmp").read() }""",
      "leaks into outer capture set"
    )

  test("cannot use access without FileSystem capability"):
    assertCompileError(
      """access("/tmp")""",
      "no given instance of type library.FileSystem"
    )

  test("cannot use exec without ProcessPermission capability"):
    assertCompileError(
      """exec("echo", List("hi"))""",
      "no given instance of type library.ProcessPermission"
    )

  test("cannot use httpGet without Network capability"):
    assertCompileError(
      """httpGet("https://example.com")""",
      "no given instance of type library.Network"
    )

  test("println inside Classified.map is rejected by capture checker"):
    assertCompileError(
      """
      val secret = classify("password")
      secret.map(x => { println(x); x })
      """,
      "capture"
    )

  test("write inside Classified.map is rejected by capture checker"):
    assertCompileError(
      """
      val secret = classify("password")
      requestFileSystem("/tmp") {
        secret.map(x => { access("/tmp/secret.txt").write(x); x })
      }
      """,
      "capture"
    )

  test("requestFileSystem inside Classified.map is rejected by capture checker"):
    assertCompileError(
      """
      val secret = classify("password")
      secret.map { content =>
        requestFileSystem("/tmp") {
          access("/tmp/leaked.txt").write(content)
        }
        content
      }
      """,
      "capture"
    )

  test("session preserves state across library calls"):
    val manager = new SessionManager
    val sessionId = manager.createSession()

    manager.executeInSession(sessionId, """
      val testResult = requestFileSystem("/tmp") { access("/tmp").isDirectory }
    """)

    val r2 = manager.executeInSession(sessionId, "testResult")
    assert(r2.isRight)
    assert(r2.toOption.get.output.contains("true"), s"unexpected output: ${r2.toOption.get.output}")

    manager.deleteSession(sessionId)
