package tacit.mcp

import io.circe.*
import io.circe.syntax.*

/** Tool definitions for the MCP server */
object Tools:
  private val sessionToolNames: Set[String] =
    Set("create_repl_session", "execute_in_session", "delete_repl_session", "list_sessions")

  def isSessionTool(name: String): Boolean = sessionToolNames.contains(name)

  val all: List[Tool] = List(
    Tool(
      name = "execute_scala",
      description = Some("Execute a Scala code snippet and return the output. This is stateless - each execution is independent. The library API is pre-loaded: use requestFileSystem(root){ ... }, access(path), grep/grepRecursive/find for files; requestExecPermission(cmds){ exec(...) } for processes; requestNetwork(hosts){ httpGet/httpPost(...) } for HTTP."),
      inputSchema = Json.obj(
        "type" -> "object".asJson,
        "properties" -> Json.obj(
          "code" -> Json.obj(
            "type" -> "string".asJson,
            "description" -> "The Scala code to execute".asJson
          )
        ),
        "required" -> Json.arr("code".asJson)
      )
    ),
    Tool(
      name = "create_repl_session",
      description = Some("Create a new Scala REPL session. Returns a session ID that can be used for subsequent executions."),
      inputSchema = Json.obj(
        "type" -> "object".asJson,
        "properties" -> Json.obj()
      )
    ),
    Tool(
      name = "delete_repl_session",
      description = Some("Delete a Scala REPL session by its ID."),
      inputSchema = Json.obj(
        "type" -> "object".asJson,
        "properties" -> Json.obj(
          "session_id" -> Json.obj(
            "type" -> "string".asJson,
            "description" -> "The ID of the session to delete".asJson
          )
        ),
        "required" -> Json.arr("session_id".asJson)
      )
    ),
    Tool(
      name = "execute_in_session",
      description = Some("Execute Scala code in an existing REPL session. The session maintains state between executions. The library API is pre-loaded: use requestFileSystem(root){ ... }, access(path), grep/grepRecursive/find for files; requestExecPermission(cmds){ exec(...) } for processes; requestNetwork(hosts){ httpGet/httpPost(...) } for HTTP."),
      inputSchema = Json.obj(
        "type" -> "object".asJson,
        "properties" -> Json.obj(
          "session_id" -> Json.obj(
            "type" -> "string".asJson,
            "description" -> "The ID of the REPL session".asJson
          ),
          "code" -> Json.obj(
            "type" -> "string".asJson,
            "description" -> "The Scala code to execute".asJson
          )
        ),
        "required" -> Json.arr("session_id".asJson, "code".asJson)
      )
    ),
    Tool(
      name = "list_sessions",
      description = Some("List all active REPL session IDs."),
      inputSchema = Json.obj(
        "type" -> "object".asJson,
        "properties" -> Json.obj()
      )
    ),
    Tool(
      name = "show_interface",
      description = Some("Show the full capability-scoped API available in the REPL. Call this first to understand what methods you can use. You must only use the provided interface to interact with the system."),
      inputSchema = Json.obj(
        "type" -> "object".asJson,
        "properties" -> Json.obj()
      )
    )
  )
