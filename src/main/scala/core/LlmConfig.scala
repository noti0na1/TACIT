package tacit
package core

/** LLM configuration for the MCP server.
  * This is the server-side config; the library has its own LlmConfig class.
  */
case class LlmConfig(baseUrl: String, apiKey: String, model: String):
  override def toString: String = s"LlmConfig($baseUrl, ***, $model)" // hide API key
