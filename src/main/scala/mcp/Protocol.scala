package mcp

import io.circe.*
import io.circe.generic.semiauto.*

/** JSON-RPC 2.0 Request */
case class JsonRpcRequest(
    jsonrpc: String,
    method: String,
    params: Option[Json],
    id: Option[Json]
)

object JsonRpcRequest:
  given Decoder[JsonRpcRequest] = deriveDecoder
  given Encoder[JsonRpcRequest] = deriveEncoder

/** JSON-RPC 2.0 Response */
case class JsonRpcResponse(
    jsonrpc: String = "2.0",
    result: Option[Json] = None,
    error: Option[JsonRpcError] = None,
    id: Option[Json]
)

object JsonRpcResponse:
  given Encoder[JsonRpcResponse] = deriveEncoder
  
  def success(id: Option[Json], result: Json): JsonRpcResponse =
    JsonRpcResponse(result = Some(result), id = id)
    
  def error(id: Option[Json], code: Int, message: String, data: Option[Json] = None): JsonRpcResponse =
    JsonRpcResponse(error = Some(JsonRpcError(code, message, data)), id = id)

/** JSON-RPC 2.0 Error */
case class JsonRpcError(
    code: Int,
    message: String,
    data: Option[Json] = None
)

object JsonRpcError:
  given Encoder[JsonRpcError] = deriveEncoder
  
  // Standard JSON-RPC error codes
  val ParseError = -32700
  val InvalidRequest = -32600
  val MethodNotFound = -32601
  val InvalidParams = -32602
  val InternalError = -32603

/** MCP Protocol Types */
case class ServerInfo(name: String, version: String)
object ServerInfo:
  given Encoder[ServerInfo] = deriveEncoder

case class Implementation(name: String, version: String)
object Implementation:
  given Encoder[Implementation] = deriveEncoder
  given Decoder[Implementation] = deriveDecoder

case class ClientCapabilities(roots: Option[Json] = None, sampling: Option[Json] = None)
object ClientCapabilities:
  given Decoder[ClientCapabilities] = deriveDecoder

case class ServerCapabilities(tools: Option[ToolsCapability] = None)
object ServerCapabilities:
  given Encoder[ServerCapabilities] = deriveEncoder

case class ToolsCapability(listChanged: Option[Boolean] = None)
object ToolsCapability:
  given Encoder[ToolsCapability] = deriveEncoder

case class InitializeParams(
    protocolVersion: String,
    capabilities: ClientCapabilities,
    clientInfo: Implementation
)
object InitializeParams:
  given Decoder[InitializeParams] = deriveDecoder

case class InitializeResult(
    protocolVersion: String,
    capabilities: ServerCapabilities,
    serverInfo: ServerInfo
)
object InitializeResult:
  given Encoder[InitializeResult] = deriveEncoder

case class Tool(
    name: String,
    description: Option[String],
    inputSchema: Json
)
object Tool:
  given Encoder[Tool] = deriveEncoder

case class ToolsListResult(tools: List[Tool])
object ToolsListResult:
  given Encoder[ToolsListResult] = deriveEncoder

case class CallToolParams(name: String, arguments: Option[Json])
object CallToolParams:
  given Decoder[CallToolParams] = deriveDecoder

case class TextContent(`type`: String, text: String)
object TextContent:
  given Encoder[TextContent] = deriveEncoder
  def apply(text: String): TextContent = TextContent("text", text)

case class CallToolResult(content: List[TextContent], isError: Option[Boolean] = None)
object CallToolResult:
  given Encoder[CallToolResult] = deriveEncoder
