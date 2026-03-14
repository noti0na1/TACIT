package tacit
package executor

import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import core.{Config, Context}
import Context.*

/** Result of code execution */
case class ExecutionResult(
    success: Boolean,
    output: String,
    error: Option[String] = None
)

/** Executes Scala code snippets via external scala-cli process */
object ScalaExecutor:

  /** Compiler flags passed to scala-cli for code execution. */
  private[executor] val compilerFlags: List[String] = List(
    "-language:experimental.captureChecking",
    "-language:experimental.modularity",
    "-Yexplicit-nulls",
    "-Wsafe-init",
    "-deprecation",
    "-feature",
    "-unchecked",
    "-color:never",
  )

  /** Builds the API initialization code (without imports). */
  private[executor] def apiInitCode(cfg: Config): String =
    val classifiedExpr =
      if cfg.classifiedPaths.isEmpty then "Set.empty[java.nio.file.Path]"
      else cfg.classifiedPaths
        .map(p => s"""java.nio.file.Path.of("$p").toAbsolutePath.normalize""")
        .mkString("Set(", ", ", ")")
    def esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    val llmConfigExpr = cfg.llmConfig match
      case None => "None"
      case Some(llm) => s"""Some(LlmConfig("${esc(llm.baseUrl)}", "${esc(llm.apiKey)}", "${esc(llm.model)}"))"""
    s"""|val api: Interface = new InterfaceImpl(
        |  (root, check, classified) => new RealFileSystem(java.nio.file.Path.of(root), check, classified),
        |  ${cfg.strictMode},
        |  $classifiedExpr,
        |  $llmConfigExpr
        |)
        |import api.*
        |given IOCapability = null.asInstanceOf[IOCapability]""".stripMargin

  /** Preamble code for REPL sessions (imports + api init). */
  private[executor] def replPreamble(cfg: Config): String =
    s"""|import tacit.library.*
        |${apiInitCode(cfg)}
        |""".stripMargin

  /** Returns Some(errorResult) on validation failure, None on success. */
  private[executor] def validated(code: String): Option[ExecutionResult] =
    CodeValidator.validate(code).left.toOption.map(violations =>
      ExecutionResult(false, "", Some(CodeValidator.formatErrors(violations)))
    )

  /** Build a complete .scala source file with directives + @main wrapping user code. */
  private def buildScript(code: String, cfg: Config): String =
    val sb = StringBuilder()
    // scala-cli directives
    sb.append("//> using scala 3.nightly\n")
    cfg.libraryJarPath.foreach(jar => sb.append(s"""//> using jar "$jar"\n"""))
    for flag <- compilerFlags do
      sb.append(s"//> using option $flag\n")
    sb.append("\n")
    sb.append("import tacit.library.*\n\n")
    // Wrap everything in @main to avoid capture checking issues with top-level givens
    sb.append("@main def __run() =\n")
    // API init code (indented inside @main)
    for line <- apiInitCode(cfg).linesIterator do
      sb.append(s"  $line\n")
    sb.append("\n")
    // User code wrapped in a block; auto-print the result (like REPL behavior)
    sb.append("  val __result__ : Any = {\n")
    for line <- code.linesIterator do
      sb.append(s"    $line\n")
    sb.append("  }\n")
    sb.append("  __result__ match\n")
    sb.append("    case _: Unit => ()\n")
    sb.append("    case other => println(other)\n")
    sb.append("\n")
    sb.toString

  /** Execute a Scala code snippet statelessly via scala-cli and return the result. */
  def execute(code: String)(using Context): ExecutionResult =
    validated(code).getOrElse:
      val cfg = ctx.config
      val scriptContent = buildScript(code, cfg)
      val tempFile = Files.createTempFile("safexec-", ".scala")
      try
        Files.writeString(tempFile, scriptContent, StandardCharsets.UTF_8)
        runScalaCli(cfg.scalaCliPath, List("run", "--server=false", tempFile.toString), timeoutSeconds = 120)
      finally
        Files.deleteIfExists(tempFile)

  /** Run a scala-cli command and capture output. */
  private[executor] def runScalaCli(
    scalaCliPath: String,
    args: List[String],
    timeoutSeconds: Long = 120,
    input: Option[String] = None
  ): ExecutionResult =
    val cmd = scalaCliPath :: args
    val pb = new ProcessBuilder(cmd*)
    pb.redirectErrorStream(true)
    val process = pb.start()

    // Write input if provided
    input.foreach { data =>
      val writer = new PrintWriter(process.getOutputStream, true)
      writer.print(data)
      writer.flush()
      writer.close()
    }

    // Read all output
    val reader = new BufferedReader(new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
    val output = StringBuilder()
    var line = reader.readLine()
    while line != null do
      output.append(line).append("\n")
      line = reader.readLine()
    reader.close()

    val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if !completed then
      process.destroyForcibly()
      ExecutionResult(false, output.toString.trim, Some("Execution timed out"))
    else
      val exitCode = process.exitValue()
      val out = output.toString.trim
      if exitCode == 0 then
        ExecutionResult(true, out)
      else
        // For non-zero exit, the output contains the error
        ExecutionResult(false, out, None)

end ScalaExecutor

/** A REPL session backed by a persistent scala-cli repl process.
  *
  * JLine's "dumb terminal" mode (used when stdin is a pipe) discards
  * buffered input lines if multiple lines arrive before the REPL reads them.
  * To work around this, we read stdout character-by-character, waiting for
  * the `scala> ` prompt before sending the next input line.
  */
class ReplSession(val id: String)(using Context):
  import ScalaExecutor.*

  private val Prompt = "scala> "

  private val cfg = ctx.config
  private val cmd: List[String] =
    val base = List(cfg.scalaCliPath, "repl", "--server=false", "-S", "3.nightly")
    val jarArgs = cfg.libraryJarPath.toList.flatMap(jar => List("--jar", jar))
    val flagArgs = ScalaExecutor.compilerFlags.flatMap(f => List("--scalac-option", f))
    base ++ jarArgs ++ flagArgs

  private val process: Process =
    val pb = new ProcessBuilder(cmd*)
    pb.redirectErrorStream(true)
    pb.start()

  private val stdin = new PrintWriter(process.getOutputStream, true)
  private val stdoutStream = process.getInputStream

  // Initialize: wait for first prompt, then feed preamble line by line
  locally:
    readUntilPrompt() // wait for initial "scala> "
    for line <- replPreamble(cfg).linesIterator do
      sendLine(line)
      readUntilPrompt() // wait for REPL to process each line

  /** Execute code in this session and return the result */
  def execute(code: String): ExecutionResult =
    validated(code).getOrElse:
      val output = StringBuilder()
      for line <- code.linesIterator do
        sendLine(line)
        output.append(readUntilPrompt())
      val cleaned = cleanReplOutput(output.toString)
      val hasErrors = cleaned.linesIterator.exists(l =>
        l.startsWith("-- [E") || l.startsWith("-- Error"))
      ExecutionResult(!hasErrors, cleaned.trim)

  /** Send a single line to the REPL's stdin. */
  private def sendLine(line: String): Unit =
    stdin.println(line)
    stdin.flush()

  /** Read stdout character-by-character until the REPL prompt appears.
    * Returns everything read before the prompt (includes newlines).
    * Handles both `scala> ` (ready) and `     | ` (continuation) prompts;
    * for continuation prompts, returns immediately so the next line can be sent.
    */
  private def readUntilPrompt(): String =
    val sb = StringBuilder()
    var done = false
    while !done do
      val ch = stdoutStream.read()
      if ch == -1 then
        done = true // EOF — process exited
      else
        sb.append(ch.toChar)
        val s = sb.toString
        if s.endsWith(Prompt) then
          done = true
          // Remove the trailing prompt from output
          sb.delete(sb.length - Prompt.length, sb.length)
        else if s.endsWith("     | ") then
          done = true
          sb.delete(sb.length - "     | ".length, sb.length)
    sb.toString

  private def cleanReplOutput(raw: String): String =
    raw.linesIterator
      .filterNot(l =>
        val trimmed = l.trim
        trimmed.startsWith("scala>") || trimmed == "|")
      .mkString("\n")

  def destroy(): Unit =
    try stdin.close() catch case _: Exception => ()
    process.destroyForcibly()

object ReplSession:
  def create(using Context): ReplSession = new ReplSession(UUID.randomUUID().toString)

/** Manages multiple REPL sessions */
class SessionManager(using Context):
  private val sessions = scala.collection.mutable.Map[String, ReplSession]()

  /** Create a new session and return its ID */
  def createSession(): String =
    val session = ReplSession.create
    sessions(session.id) = session
    session.id

  /** Delete a session by ID */
  def deleteSession(sessionId: String): Boolean =
    sessions.remove(sessionId) match
      case Some(session) =>
        session.destroy()
        true
      case None => false

  /** Get a session by ID */
  def getSession(sessionId: String): Option[ReplSession] =
    sessions.get(sessionId)

  /** Execute code in a specific session */
  def executeInSession(sessionId: String, code: String): Either[String, ExecutionResult] =
    sessions.get(sessionId) match
      case Some(session) => Right(session.execute(code))
      case None => Left(s"Session not found: $sessionId")

  /** List all active session IDs */
  def listSessions(): List[String] =
    sessions.keys.toList
