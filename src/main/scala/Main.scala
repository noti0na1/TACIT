package tacit

import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import tacit.mcp.*
import tacit.core.Config
import tacit.core.*
import Context.*

/** SafeExecMCP - A Model Context Protocol server for safe Scala code execution */
@main def SafeExecMCP(args: String*): Unit =
  Config.parseCliArgs(args.toArray) match
    case None =>  // Errors should have been displayed by the parser
    case Some(config) =>
      // Validate that library JAR path is set
      config.libraryJarPath match
        case None =>
          System.err.println("[SafeExecMCP] ERROR: --library-jar is required. Provide path to the compiled library JAR.")
          System.exit(1)
        case Some(jarPath) =>
          val jarFile = java.io.File(jarPath)
          if !jarFile.exists() then
            System.err.println(s"[SafeExecMCP] ERROR: Library JAR not found: $jarPath")
            System.exit(1)

      usingContext(config):
        val server = new McpServer
        val reader = new BufferedReader(new InputStreamReader(System.in))
        val writer = new PrintWriter(System.out, true)

        // Log to stderr so it doesn't interfere with JSON-RPC communication
        def log(msg: String): Unit =
          if !config.quiet then System.err.println(s"[SafeExecMCP] $msg")
        def error(msg: String): Unit =
          System.err.println(s"[SafeExecMCP] ERROR: $msg")

        def printStartupBanner(): Unit =
          val jarPath = scala.util.Try {
            new java.io.File(classOf[McpServer].getProtectionDomain.getCodeSource.getLocation.toURI).getAbsolutePath
          }.getOrElse("<path/to/SafeExecMCP-assembly-0.1.0-SNAPSHOT.jar>")
          val cwd = System.getProperty("user.dir")
          val recordingStatus = config.recordPath match
            case Some(dir) => s"Recording:   ON -> $dir"
            case None      => "Recording:   OFF"
          val strictStatus = if config.strictMode then "Strict:      ON (file ops blocked in exec)" else "Strict:      OFF"
          val sessionStatus = if config.sessionEnabled then "Sessions:    ON" else "Sessions:    OFF"
          val llmStatus = config.llmConfig match
            case Some(cfg) => s"LLM:         ON -> ${cfg.model} @ ${cfg.baseUrl}"
            case None      => "LLM:         OFF"
          val scalaCliStatus = s"scala-cli:   ${config.scalaCliPath}"
          val libraryStatus = s"Library JAR: ${config.libraryJarPath.getOrElse("NOT SET")}"

          System.err.println(
            s"""
              | SafeExecMCP Server
              | Transport: stdio (JSON-RPC 2.0)
              | Protocol:  Model Context Protocol (MCP)
              | $recordingStatus
              | $strictStatus
              | $sessionStatus
              | $llmStatus
              | $scalaCliStatus
              | $libraryStatus
              | JAR:         $jarPath
              | CWD:         $cwd
              |""".stripMargin)

        if !config.quiet then printStartupBanner()

        try
          var running = true
          while running do
            val line = reader.readLine()
            if line == null then
              running = false
            else if line.trim.nonEmpty then
              log(s"Received: ${line.take(200)}...")

              parse(line) match
                case Left(error) =>
                  val response = JsonRpcResponse.error(
                    None,
                    JsonRpcError.ParseError,
                    s"Parse error: ${error.message}"
                  )
                  sendResponse(writer, response, config.quiet)

                case Right(json) =>
                  json.as[JsonRpcRequest] match
                    case Left(error) =>
                      val response = JsonRpcResponse.error(
                        None,
                        JsonRpcError.InvalidRequest,
                        s"Invalid request: ${error.message}"
                      )
                      sendResponse(writer, response, config.quiet)

                    case Right(request) =>
                      server.handleRequest(request).foreach { response =>
                        sendResponse(writer, response, config.quiet)
                      }
        catch
          case e: Exception =>
            error(e.getMessage)
            e.printStackTrace(System.err)
        finally
          log("Server shutting down...")

def sendResponse(writer: PrintWriter, response: JsonRpcResponse, quiet: Boolean = false): Unit =
  val json = response.asJson.noSpaces
  if !quiet then System.err.println(s"[SafeExecMCP] Sending: ${json.take(200)}...")
  writer.println(json)
  writer.flush()
