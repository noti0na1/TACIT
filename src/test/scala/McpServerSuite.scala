import tacit.mcp.*
import io.circe.*
import io.circe.syntax.*
import tacit.core.{Context, Config}

class McpServerSuite extends munit.FunSuite:
  given defaultTestCtx: Context = Context(Config(), None)

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
