package mcp

import io.circe.*
import io.circe.syntax.*
import executor.{ScalaExecutor, SessionManager, ExecutionResult, CodeRecorder}
import core.*
import Context.*

private val InterfaceReference: String =
  val preamble =
    """|IMPORTANT: You must only use the provided interface below to interact with the system.
       |Do not use Java/Scala standard library APIs (java.io, java.nio, scala.io, sys.process, java.net, etc.) to access files, run processes, or make network requests directly.
       |All system interactions must go through the capability-scoped API so that access is properly sandboxed and auditable.
       |
       |The interface is pre-loaded and available in all code executions.
       |
       |""".stripMargin
  val source =
    val stream = classOf[McpServer].getResourceAsStream("/Interface.scala")
    if stream != null then
      try 
        val content = scala.io.Source.fromInputStream(stream)(using scala.io.Codec.UTF8).mkString
        "```scala\n" + content + "\n```"
      finally stream.close()
    else "(Interface.scala source not found on classpath)"
  preamble + source

/** MCP Server implementation for Scala code execution */
class McpServer(using Context):
  private val sessionManager = new SessionManager()

  def recorder: Option[CodeRecorder] = ctx.recorder

  /** Handle a JSON-RPC request and return a response */
  def handleRequest(request: JsonRpcRequest): Option[JsonRpcResponse] =
    request.method match
      case "initialize" =>
        handleInitialize(request)
      case "initialized" =>
        // Notification, no response needed
        None
      case "tools/list" =>
        handleToolsList(request)
      case "tools/call" =>
        handleToolsCall(request)
      case "ping" =>
        Some(JsonRpcResponse.success(request.id, Json.obj()))
      case "notifications/cancelled" =>
        // Notification, no response needed
        None
      case other =>
        Some(JsonRpcResponse.error(
          request.id,
          JsonRpcError.MethodNotFound,
          s"Method not found: $other"
        ))
  
  private def handleInitialize(request: JsonRpcRequest): Option[JsonRpcResponse] =
    val result = InitializeResult(
      protocolVersion = "2024-11-05",
      capabilities = ServerCapabilities(
        tools = Some(ToolsCapability(listChanged = Some(true)))
      ),
      serverInfo = ServerInfo(
        name = "SafeExecMCP",
        version = "0.1.0"
      )
    )
    Some(JsonRpcResponse.success(request.id, result.asJson))
  
  private def handleToolsList(request: JsonRpcRequest): Option[JsonRpcResponse] =
    val result = ToolsListResult(Tools.all)
    Some(JsonRpcResponse.success(request.id, result.asJson))
  
  private def handleToolsCall(request: JsonRpcRequest): Option[JsonRpcResponse] =
    val result = for
      params <- request.params.toRight("Missing params")
      callParams <- params.as[CallToolParams].left.map(_.message)
      toolResult <- callTool(callParams.name, callParams.arguments)
    yield toolResult
    
    result match
      case Right(toolResult) =>
        Some(JsonRpcResponse.success(request.id, toolResult.asJson))
      case Left(error) =>
        Some(JsonRpcResponse.error(
          request.id,
          JsonRpcError.InvalidParams,
          error
        ))
  
  private def callTool(name: String, arguments: Option[Json]): Either[String, CallToolResult] =
    name match
      case "execute_scala" =>
        executeScala(arguments)
      case "create_repl_session" =>
        createReplSession()
      case "delete_repl_session" =>
        deleteReplSession(arguments)
      case "execute_in_session" =>
        executeInSession(arguments)
      case "list_sessions" =>
        listSessions()
      case "show_interface" =>
        showInterface()
      case other =>
        Left(s"Unknown tool: $other")
  
  private def executeScala(arguments: Option[Json]): Either[String, CallToolResult] =
    for
      args <- arguments.toRight("Missing arguments")
      code <- args.hcursor.get[String]("code").left.map(_.message)
    yield
      val result = ScalaExecutor.execute(code)
      recorder.foreach(_.record(code, "stateless", result))
      formatExecutionResult(result)
  
  private def createReplSession(): Either[String, CallToolResult] =
    val sessionId = sessionManager.createSession()
    Right(CallToolResult(
      content = List(TextContent(s"Created REPL session: $sessionId"))
    ))
  
  private def deleteReplSession(arguments: Option[Json]): Either[String, CallToolResult] =
    for
      args <- arguments.toRight("Missing arguments")
      sessionId <- args.hcursor.get[String]("session_id").left.map(_.message)
    yield
      if sessionManager.deleteSession(sessionId) then
        CallToolResult(content = List(TextContent(s"Deleted session: $sessionId")))
      else
        CallToolResult(
          content = List(TextContent(s"Session not found: $sessionId")),
          isError = Some(true)
        )
  
  private def executeInSession(arguments: Option[Json]): Either[String, CallToolResult] =
    for
      args <- arguments.toRight("Missing arguments")
      sessionId <- args.hcursor.get[String]("session_id").left.map(_.message)
      code <- args.hcursor.get[String]("code").left.map(_.message)
      result <- sessionManager.executeInSession(sessionId, code)
    yield
      recorder.foreach(_.record(code, sessionId, result))
      formatExecutionResult(result)
  
  private def listSessions(): Either[String, CallToolResult] =
    val sessions = sessionManager.listSessions()
    val text = if sessions.isEmpty then
      "No active sessions"
    else
      s"Active sessions:\n${sessions.mkString("\n")}"
    Right(CallToolResult(content = List(TextContent(text))))
  
  private def showInterface(): Either[String, CallToolResult] =
    Right(CallToolResult(content = List(TextContent(InterfaceReference))))

  private def formatExecutionResult(result: ExecutionResult): CallToolResult =
    val output = result.error match
      case Some(err) if result.output.nonEmpty =>
        s"${result.output}\n\nError: $err"
      case Some(err) =>
        s"Error: $err"
      case None =>
        if result.output.isEmpty then "(no output)" else result.output
    
    CallToolResult(
      content = List(TextContent(output)),
      isError = if result.success then None else Some(true)
    )
