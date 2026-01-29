import executor.{ScalaExecutor, SessionManager}
import mcp.*
import io.circe.*
import io.circe.syntax.*

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

class SessionManagerSuite extends munit.FunSuite:
  
  test("create and list sessions"):
    val manager = new SessionManager()
    val sessionId = manager.createSession()
    assert(sessionId.nonEmpty)
    assert(manager.listSessions().contains(sessionId))
  
  test("delete session"):
    val manager = new SessionManager()
    val sessionId = manager.createSession()
    assert(manager.deleteSession(sessionId))
    assert(!manager.listSessions().contains(sessionId))
  
  test("delete non-existent session"):
    val manager = new SessionManager()
    assert(!manager.deleteSession("non-existent-id"))
  
  test("execute in session maintains state"):
    val manager = new SessionManager()
    val sessionId = manager.createSession()
    
    // Define a variable
    val result1 = manager.executeInSession(sessionId, "val x = 42")
    assert(result1.isRight)
    
    // Use the variable in next execution
    val result2 = manager.executeInSession(sessionId, "x * 2")
    assert(result2.isRight)
    assert(result2.toOption.get.output.contains("84"))
  
  test("execute in non-existent session"):
    val manager = new SessionManager()
    val result = manager.executeInSession("non-existent-id", "1 + 1")
    assert(result.isLeft)

class McpServerSuite extends munit.FunSuite:
  
  test("initialize request"):
    val server = new McpServer()
    val request = JsonRpcRequest(
      jsonrpc = "2.0",
      method = "initialize",
      params = Some(Json.obj(
        "protocolVersion" -> "2024-11-05".asJson,
        "capabilities" -> Json.obj(),
        "clientInfo" -> Json.obj(
          "name" -> "test-client".asJson,
          "version" -> "1.0.0".asJson
        )
      )),
      id = Some(Json.fromInt(1))
    )
    
    val response = server.handleRequest(request)
    assert(response.isDefined)
    assert(response.get.error.isEmpty)
    assert(response.get.result.isDefined)
  
  test("tools/list request"):
    val server = new McpServer()
    val request = JsonRpcRequest(
      jsonrpc = "2.0",
      method = "tools/list",
      params = None,
      id = Some(Json.fromInt(1))
    )
    
    val response = server.handleRequest(request)
    assert(response.isDefined)
    assert(response.get.error.isEmpty)
    
    val tools = response.get.result.flatMap(_.hcursor.get[List[Json]]("tools").toOption)
    assert(tools.isDefined)
    assert(tools.get.nonEmpty)
  
  test("execute_scala tool"):
    val server = new McpServer()
    val request = JsonRpcRequest(
      jsonrpc = "2.0",
      method = "tools/call",
      params = Some(Json.obj(
        "name" -> "execute_scala".asJson,
        "arguments" -> Json.obj(
          "code" -> "1 + 1".asJson
        )
      )),
      id = Some(Json.fromInt(1))
    )
    
    val response = server.handleRequest(request)
    assert(response.isDefined)
    assert(response.get.error.isEmpty)
  
  test("create and use repl session"):
    val server = new McpServer()
    
    // Create session
    val createRequest = JsonRpcRequest(
      jsonrpc = "2.0",
      method = "tools/call",
      params = Some(Json.obj(
        "name" -> "create_repl_session".asJson,
        "arguments" -> Json.obj()
      )),
      id = Some(Json.fromInt(1))
    )
    
    val createResponse = server.handleRequest(createRequest)
    assert(createResponse.isDefined)
    assert(createResponse.get.error.isEmpty)
    
    // Extract session ID from response
    val content = createResponse.get.result
      .flatMap(_.hcursor.get[List[Json]]("content").toOption)
      .flatMap(_.headOption)
      .flatMap(_.hcursor.get[String]("text").toOption)
    
    assert(content.isDefined)
    val sessionId = content.get.split(": ").last.trim
    
    // Execute in session
    val execRequest = JsonRpcRequest(
      jsonrpc = "2.0",
      method = "tools/call",
      params = Some(Json.obj(
        "name" -> "execute_in_session".asJson,
        "arguments" -> Json.obj(
          "session_id" -> sessionId.asJson,
          "code" -> "val y = 100".asJson
        )
      )),
      id = Some(Json.fromInt(2))
    )
    
    val execResponse = server.handleRequest(execRequest)
    assert(execResponse.isDefined)
    assert(execResponse.get.error.isEmpty)

